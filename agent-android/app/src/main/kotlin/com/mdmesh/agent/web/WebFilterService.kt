package com.mdmesh.agent.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.Executors

/** DNS-only local VPN: only the virtual DNS address is routed, keeping app traffic on the kernel fast path. */
class WebFilterService : VpnService() {
    @Volatile private var tun: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor()
    // A slow upstream must not hold up the packet reader or unrelated permitted lookups.
    private val forwarding = java.util.concurrent.ThreadPoolExecutor(
        4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )
    @Volatile private var policy = WebFilterPolicy("OFF", emptySet(), emptySet())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val updated = WebFilterConfig.load(this)
        // Always-on VPN and config.apply can start the same service back-to-back.
        if (updated == policy && tun != null && WebFilterRuntime.applied == updated) return START_STICKY
        policy = updated
        WebFilterRuntime.applied = null
        WebFilterRuntime.failure = null
        val previous = tun
        // Establish first: closing the last TUN before replacement races always-on VPN recovery.
        try { startTunnel() } catch (e: Exception) {
            WebFilterRuntime.failure = e.message ?: "VPN establishment failed"
            stopSelf()
        } finally {
            if (previous !== tun) previous?.close()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        val builder = Builder().setSession("MDMesh Web Filter")
            .setMtu(1500).addAddress("10.253.0.1", 30)
            .addDnsServer("10.253.0.2").addRoute("10.253.0.2", 32)
            // The management control-plane must remain reachable even if an administrator enters
            // an overly broad deny rule. Other applications still use the filtered DNS route.
            .setBlocking(false)
        if (!policy.allApps) {
            // A configuration can name an app before it is installed. PackageEventReceiver
            // schedules a config reapply after installation, which rebuilds this VPN route.
            var installed = 0
            policy.filteredPackages.forEach { pkg ->
                try { builder.addAllowedApplication(pkg); installed++ }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { /* wait for install */ }
            }
            // An empty allowlist would make the VPN apply to every app on Android.
            if (installed == 0) { tun = null; WebFilterRuntime.applied = policy; return }
        } else {
            builder.addDisallowedApplication(packageName)
        }
        tun = builder.establish()
        val descriptor = tun ?: error("VPN establishment was refused")
        val snapshot = policy
        worker.execute {
            if (tun === descriptor) {
                WebFilterRuntime.applied = snapshot
                try { loop(descriptor) } finally {
                    if (tun === descriptor) {
                        WebFilterRuntime.applied = null
                        WebFilterRuntime.failure = "DNS packet reader stopped"
                    }
                }
            }
        }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(1500)
        val poll = StructPollfd().apply {
            this.fd = fd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        // A blocking read on a closed TUN can survive on some kernels and prevent
        // the single worker from ever starting the replacement tunnel's loop.
        while (!Thread.currentThread().isInterrupted && tun === fd) {
            val size = try {
                if (Os.poll(arrayOf(poll), 250) == 0) continue
                if (tun !== fd || !fd.fileDescriptor.valid()) break
                Os.read(fd.fileDescriptor, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (tun === fd && (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR)) continue
                break
            } catch (_: Exception) {
                break
            }
            if (size <= 28 || (buffer[0].toInt() ushr 4) != 4 || buffer[9].toInt() != 17) continue
            val ihl = (buffer[0].toInt() and 0x0f) * 4
            if (ihl < 20 || ihl + 8 > size || buffer[ihl + 2].toInt() != 0 || (buffer[ihl + 3].toInt() and 255) != 53) continue
            val dnsOffset = ihl + 8
            val host = readQuestionName(buffer, dnsOffset, size)?.let(DomainMatcher::normalize)?.takeIf { it.isNotEmpty() } ?: continue
            val owner = connectionPackage(buffer, ihl)
            val blocked = policy.blocks(host, owner)
            if (com.mdmesh.agent.BuildConfig.DEBUG) android.util.Log.d("MDMeshDNS", "query $host app=$owner blocked=$blocked")
            if (blocked) NetworkAccessStore.record(this, policy.policyHash, host, owner, policy.blockedRules(host, owner))
            val query = buffer.copyOfRange(dnsOffset, size)
            val request = buffer.copyOf(size)
            val snapshot = policy
            val respond = Runnable {
                val response = if (blocked) DnsForwarder.error(query, 3) else {
                    val answer = upstream(query)
                    if (snapshot.rules.isNotEmpty() || snapshot.grants.isNotEmpty()) DnsForwarder.withoutCache(answer) else answer
                }
                if (com.mdmesh.agent.BuildConfig.DEBUG) android.util.Log.d("MDMeshDNS", "reply $host rcode=${response[3].toInt() and 15} bytes=${response.size} current=${tun === fd}")
                if (tun === fd) synchronized(output) {
                    if (tun === fd) runCatching { output.write(ipv4UdpReply(request, ihl, response)) }.onFailure {
                        if (com.mdmesh.agent.BuildConfig.DEBUG) android.util.Log.e("MDMeshDNS", "reply write failed", it)
                    }
                }
            }
            if (blocked) respond.run() else try { forwarding.execute(respond) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                if (tun === fd) synchronized(output) {
                    if (tun === fd) runCatching { output.write(ipv4UdpReply(request, ihl, DnsForwarder.error(query, 2))) }
                }
            }
        }
    }

    private fun connectionPackage(packet: ByteArray, ihl: Int): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val local = java.net.InetSocketAddress(java.net.InetAddress.getByAddress(packet.copyOfRange(12, 16)), ((packet[ihl].toInt() and 255) shl 8) or (packet[ihl+1].toInt() and 255))
            val remote = java.net.InetSocketAddress(java.net.InetAddress.getByAddress(packet.copyOfRange(16, 20)), 53)
            val uid = getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote)
            if (uid < 10000) return null
            // Shared UIDs cannot be narrowed safely to one app.
            packageManager.getPackagesForUid(uid)?.singleOrNull()?.takeIf { it != packageName && (policy.allApps || it in policy.filteredPackages) }
        }.getOrNull()
    }

    private fun upstream(query: ByteArray): ByteArray {
        // Refresh per query: a Wi-Fi/mobile handover must not retain the old network's DNS.
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val active = connectivity.activeNetwork
        val network = connectivity.allNetworks.filter {
            val caps = connectivity.getNetworkCapabilities(it)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.sortedBy {
            when {
                it == active -> 0
                connectivity.getNetworkCapabilities(it)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> 1
                else -> 2
            }
        }.firstOrNull()
        val servers = network?.let { connectivity.getLinkProperties(it)?.dnsServers }.orEmpty()
        return DnsForwarder.resolve(query, servers) { resolver, request ->
            DatagramSocket().use { socket ->
                check(protect(socket)) { "Could not protect DNS socket" }
                socket.soTimeout = 1000
                // Connected UDP accepts replies only from the chosen resolver.
                socket.connect(resolver, 53)
                socket.send(DatagramPacket(request, request.size))
                val reply = ByteArray(4096)
                val packet = DatagramPacket(reply, reply.size)
                socket.receive(packet)
                reply.copyOf(packet.length)
            }
        }
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

    override fun onDestroy() { WebFilterRuntime.applied = null; tun?.close(); tun = null; worker.shutdownNow(); forwarding.shutdownNow(); super.onDestroy() }
}
