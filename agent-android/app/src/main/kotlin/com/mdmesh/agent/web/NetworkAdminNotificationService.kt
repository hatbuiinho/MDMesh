package com.mdmesh.agent.web

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mdmesh.agent.R
import com.mdmesh.core.config.ServerConfigStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.util.concurrent.TimeUnit

/** Screen-on delivery only: no alarms, wake locks, FCM, or network polling while asleep. */
class NetworkAdminNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var account: NetworkAdminAccount
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var receiverRegistered = false
    private var networkRegistered = false
    private val screen get() = getSystemService(PowerManager::class.java).isInteractive
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { reevaluate() }
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch { reevaluate() } }
        override fun onLost(network: Network) { socket?.cancel(); socket = null }
    }
    override fun onCreate() {
        super.onCreate(); account = NetworkAdminAccount(this)
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network); networkRegistered = true }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels()
        startForeground(SERVICE_ID, NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("MDMesh · Nhận yêu cầu admin")
            .setContentText("Kiểm tra khi bật màn hình hoặc mở khóa").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openIntent()).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        if (account.token() == null) { stopSelf(); return START_NOT_STICKY }
        reevaluate(); return START_STICKY
    }
    @Synchronized private fun reevaluate() {
        if (!screen || account.token() == null) {
            loop?.cancel(); loop = null; socket?.cancel(); socket = null
            if (account.token() == null) stopSelf()
            return
        }
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive && screen) {
                connect()
                checkRequests()
                delay(30_000)
            }
        } else scope.launch { checkRequests() }
    }
    @Synchronized private fun connect() {
        val token = account.token() ?: return
        if (!screen || socket != null) return
        val base = ServerConfigStore(this).baseUrl()
        if (!base.startsWith("https://")) return
        socket = http.newWebSocket(Request.Builder().url(base.replaceFirst("https://", "wss://") + "/agent/ws/admin-notifications")
            .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { if (text == "{\"wake\":\"networkAccess\"}" && screen) scope.launch { checkRequests() } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (socket === webSocket) socket = null }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (socket === webSocket) socket = null }
        })
    }
    private suspend fun checkRequests() = mutex.withLock {
        if (!screen) return@withLock
        val token = account.token() ?: return@withLock
        try {
            val data = NetworkAccessClient(this).receive(token).getJSONObject("data")
            if (!screen || account.token() != token) return@withLock
            val count = data.optInt("pendingCount"); val latest = data.optInt("latestId")
            val manager = getSystemService(NotificationManager::class.java)
            if (count == 0) { manager.cancel(REQUESTS_ID); return@withLock }
            val fresh = latest > account.notifiedId()
            val active = manager.activeNotifications.any { it.id == REQUESTS_ID }
            if (!fresh && !active) return@withLock
            val notification = NotificationCompat.Builder(this, REQUESTS_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("MDMesh · Yêu cầu mở truy cập mạng")
                .setContentText("Có $count yêu cầu đang chờ duyệt")
                .setNumber(count).setOnlyAlertOnce(!fresh).setAutoCancel(true).setContentIntent(openIntent())
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(this, REQUESTS_CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("MDMesh").setContentText("Có yêu cầu quản trị đang chờ").setContentIntent(openIntent()).build()).build()
            if (manager.areNotificationsEnabled()) { manager.notify(REQUESTS_ID, notification); account.notified(latest) }
        } catch (_: NetworkAccessRejected) {
            account.clear(); getSystemService(NotificationManager::class.java).cancel(REQUESTS_ID); stopSelf()
        } catch (_: Exception) { /* Offline: reconnect or next screen-on will retry; never wake the device. */ }
    }
    private fun channels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "Nhận yêu cầu admin", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(REQUESTS_CHANNEL, "Yêu cầu mở truy cập mạng", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    private fun openIntent() = PendingIntent.getActivity(this, 4011, Intent(this, NetworkAccessActivity::class.java)
        .putExtra("adminInbox", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(receiver)
        if (networkRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        loop?.cancel(); socket?.cancel(); scope.cancel(); http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
    companion object {
        const val REQUESTS_ID = 4012
        private const val SERVICE_ID = 4011
        private const val SERVICE_CHANNEL = "network_admin_receiver"
        private const val REQUESTS_CHANNEL = "network_admin_requests"
        fun start(context: Context) { if (NetworkAdminAccount(context).token() != null) ContextCompat.startForegroundService(context, Intent(context, NetworkAdminNotificationService::class.java)) }
        fun stop(context: Context) {
            context.stopService(Intent(context, NetworkAdminNotificationService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(REQUESTS_ID)
        }
    }
}
