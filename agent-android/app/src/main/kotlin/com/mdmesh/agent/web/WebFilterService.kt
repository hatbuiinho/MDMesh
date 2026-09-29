package com.mdmesh.agent.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/** DNS-only local VPN: only the virtual DNS address is routed, keeping app traffic on the kernel fast path. */
class WebFilterService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var policy = WebFilterPolicy("OFF", emptySet(), emptySet())
    @Volatile private var resolver: InetAddress = InetAddress.getByName("1.1.1.1")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        policy = WebFilterConfig.load(this)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        resolver = connectivity.allNetworks.asSequence()
            .filter { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .mapNotNull { connectivity.getLinkProperties(it)?.dnsServers?.firstOrNull() }
            .firstOrNull() ?: resolver
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        tun = Builder().setSession("MDMesh Web Filter")
            .setMtu(1500).addAddress("10.253.0.1", 30)
            .addDnsServer("10.253.0.2").addRoute("10.253.0.2", 32)
            // The management control-plane must remain reachable even if an administrator enters
            // an overly broad deny rule. Other applications still use the filtered DNS route.
            .addDisallowedApplication(packageName)
            .setBlocking(true).establish()
        val descriptor = tun ?: return
        worker.execute { loop(descriptor) }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(1500)
        while (!Thread.currentThread().isInterrupted) {
            val size = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (size <= 28 || (buffer[0].toInt() ushr 4) != 4 || buffer[9].toInt() != 17) continue
            val ihl = (buffer[0].toInt() and 0x0f) * 4
            val dnsOffset = ihl + 8
            val host = readQuestionName(buffer, dnsOffset, size) ?: continue
            val blocked = policy.blocks(host)
            val query = buffer.copyOfRange(dnsOffset, size)
            val response = if (blocked) nxdomain(query) else upstream(query) ?: continue
            output.write(ipv4UdpReply(buffer, ihl, response))
        }
    }

    private fun upstream(query: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { socket ->
            protect(socket); socket.soTimeout = 4000
            socket.send(DatagramPacket(query, query.size, resolver, 53))
            val reply = ByteArray(4096); val packet = DatagramPacket(reply, reply.size); socket.receive(packet)
            reply.copyOf(packet.length)
        }
    }.getOrNull()

    private fun nxdomain(query: ByteArray): ByteArray = query.copyOf().also {
        it[2] = ((it[2].toInt() and 0x01) or 0x80).toByte(); it[3] = 0x83.toByte()
        for (i in 6..11) it[i] = 0
    }

    private fun readQuestionName(packet: ByteArray, offset: Int, size: Int): String? {
        if (offset + 13 >= size) return null
        var p = offset + 12; val labels = ArrayList<String>(4)
        while (p < size) {
            val len = packet[p++].toInt() and 0xff
            if (len == 0) break
            if (len > 63 || p + len > size) return null
            labels += packet.copyOfRange(p, p + len).toString(Charsets.US_ASCII); p += len
        }
        return labels.joinToString(".")
    }

    private fun ipv4UdpReply(request: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = ihl + 8 + dns.size; val out = ByteArray(total)
        request.copyInto(out, 0, 0, ihl + 8); out[2] = (total ushr 8).toByte(); out[3] = total.toByte()
        for (i in 12..15) { out[i] = request[i + 4]; out[i + 4] = request[i] }
        out[ihl] = request[ihl + 2]; out[ihl + 1] = request[ihl + 3]
        out[ihl + 2] = request[ihl]; out[ihl + 3] = request[ihl + 1]
        val udpLen = 8 + dns.size; out[ihl + 4] = (udpLen ushr 8).toByte(); out[ihl + 5] = udpLen.toByte()
        out[ihl + 6] = 0; out[ihl + 7] = 0; dns.copyInto(out, ihl + 8)
        out[10] = 0; out[11] = 0; val sum = checksum(out, 0, ihl); out[10] = (sum ushr 8).toByte(); out[11] = sum.toByte()
        return out
    }

    private fun checksum(bytes: ByteArray, start: Int, length: Int): Int {
        var sum = 0L; var i = start
        while (i < start + length) { sum += ((bytes[i].toInt() and 255) shl 8) + (bytes[i + 1].toInt() and 255); i += 2 }
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun foreground() {
        val channel = "web_filter"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channel, "Web access policy", NotificationManager.IMPORTANCE_LOW))
        startForeground(43, NotificationCompat.Builder(this, channel).setSmallIcon(com.mdmesh.agent.R.mipmap.ic_launcher)
            .setContentTitle("Web access protected").setContentText("MDMesh domain policy is active").setOngoing(true).build())
    }

    override fun onDestroy() { tun?.close(); tun = null; worker.shutdownNow(); super.onDestroy() }
}
