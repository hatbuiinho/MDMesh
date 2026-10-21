package com.mdmesh.agent.apps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mdmesh.agent.R
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.DeviceIdStore
import java.security.MessageDigest

/** Device-authenticated results only; admin inbox results must never enter this notifier. */
internal object AppApprovalNotifications {
    private const val CHANNEL = "app_install_approvals"
    private const val TAG = "app-install-approval"
    suspend fun scope(context: Context): String? {
        val device = DeviceIdStore(context).current() ?: return null
        val value = ServerConfigStore(context).baseUrl() + "|" + device
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    suspend fun count(context: Context): Int {
        val prefs = context.getSharedPreferences("app_approval_notifications", Context.MODE_PRIVATE)
        return if (prefs.getString("scope", "") == scope(context)) prefs.getInt("count", 0) else 0
    }
    @Synchronized fun installed(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("app_approval_notifications", Context.MODE_PRIVATE)
        val packages = prefs.getStringSet("packages", emptySet()).orEmpty()
        val ids = packages.filter { it.substringAfter(":") == packageName }.map { it.substringBefore(":") }.toSet()
        ids.forEach { context.getSystemService(NotificationManager::class.java).cancel(TAG, it.toInt()) }
        val active = prefs.getStringSet("active", emptySet()).orEmpty() - ids
        prefs.edit().putStringSet("active", active).putInt("count", active.size)
            .putStringSet("packages", packages.filterNot { it.substringBefore(":") in ids }.toSet()).apply()
    }
    @Synchronized fun update(context: Context, scope: String, rows: List<AppInstallRequest>) {
        val prefs = context.getSharedPreferences("app_approval_notifications", Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ứng dụng đã được duyệt", NotificationManager.IMPORTANCE_DEFAULT))
        val previousScope = prefs.getString("scope", "")
        val previousActive = prefs.getStringSet("active", emptySet()).orEmpty().toSet()
        if (previousScope != scope) previousActive.forEach { manager.cancel(TAG, it.toInt()) }
        val seen = if (previousScope == scope) prefs.getStringSet("seen", emptySet()).orEmpty().toMutableSet() else mutableSetOf()
        val localRows = rows.map { row ->
            if (row.status == "approved" && row.installedAt == null &&
                runCatching { context.packageManager.getPackageInfo(row.packageName, 0) }.isSuccess)
                row.copy(installedAt = System.currentTimeMillis()) else row
        }
        val plan = approvalNotificationPlan(localRows, seen, if (previousScope == scope) previousActive else emptySet())
        val ready = plan.ready
        val active = ready.map { it.id.toString() }.toSet()
        plan.cancel.forEach { manager.cancel(TAG, it.toInt()) }
        val channelEnabled = Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled) {
            plan.post.forEach { row ->
                val intent = Intent(context, AppRequestsActivity::class.java)
                    .setData(Uri.parse("mdmesh://app-approval/$scope/${row.id}"))
                    .putExtra("installRequestId", row.id).putExtra("approvalScope", scope)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val open = PendingIntent.getActivity(context, row.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val text = "Quản trị viên đã duyệt yêu cầu. Nhấn để cài từ Google Play."
                val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Đã cho phép cài ${row.appName}").setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setContentIntent(open).addAction(0, "Cài ứng dụng", open)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("MDMesh").setContentText("Có ứng dụng đã được duyệt").setContentIntent(open).build()).build()
                try { manager.notify(TAG, row.id, notification); seen += row.id.toString() }
                catch (_: SecurityException) { /* Permission may change between checking and posting. */ }
            }
        }
        prefs.edit().putString("scope", scope).putStringSet("active", active).putStringSet("seen", seen)
            .putStringSet("packages", ready.map { "${it.id}:${it.packageName}" }.toSet())
            .putInt("count", ready.size).apply()
    }
}
