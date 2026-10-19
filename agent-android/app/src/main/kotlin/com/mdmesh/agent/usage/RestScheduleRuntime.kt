package com.mdmesh.agent.usage

import android.app.*
import android.content.*
import android.os.Build
import com.mdmesh.core.usage.RestScheduleEngine
import com.mdmesh.proto.AppUsagePolicy
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Local-only boundary scheduling and notifications. No network operation occurs here. */
object RestScheduleRuntime {
    private const val STATUS_ID = 7310
    private const val ALERT_ID = 7311
    private const val CHANNEL = "rest_schedule_status"
    private const val WARNING_CHANNEL = "rest_schedule_warning"

    @Synchronized
    fun reconcile(context: Context, policy: AppUsagePolicy?, nowMillis: Long) {
        val now = Instant.ofEpochMilli(nowMillis)
        val alarms = context.getSystemService(AlarmManager::class.java)
        fun pending(code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, RestScheduleReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val occurrences = policy?.let { RestScheduleEngine.occurrences(it, now) }.orEmpty()
        // Enforcement and warnings use separate alarms. Warning alarms must not consume
        // allow-while-idle wakeup quota just before an enforcement boundary.
        val appWindows = policy?.rules.orEmpty().filter { it.enabled }.flatMap { rule ->
            rule.allowedWindows.mapIndexed { index, w -> com.mdmesh.proto.AppRestSchedule(
                "window-${rule.packageName}-$index", "", days = w.days, from = w.from, to = w.to) }
        }
        val allBoundaries = occurrences + if (policy == null) emptyList() else RestScheduleEngine.occurrences(policy.copy(schedules = appWindows), now)
        val midnight = policy?.let { now.atZone(RestScheduleEngine.zone(it)).toLocalDate().plusDays(1).atStartOfDay(RestScheduleEngine.zone(it)).toInstant() }
        val next = (allBoundaries.flatMap { listOf(it.start, it.end) } + listOfNotNull(midnight) +
            policy?.overrides.orEmpty().map { Instant.ofEpochMilli(it.expiresAt) }).filter { it > now }.minOrNull()
        val warning = occurrences.filter { it.schedule.warningMinutes > 0 }.flatMap { o ->
            listOf(o.start.minusSeconds(o.schedule.warningMinutes * 60L)) +
                if (o.schedule.warningMinutes > 1) listOf(o.start.minusSeconds(60)) else emptyList()
        }.filter { it > now }.minOrNull()
        fun schedule(code: Int, at: Instant?, wakeIdle: Boolean) {
            val pi = pending(code)
            if (at == null) { alarms.cancel(pi); return }
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                try {
                    if (wakeIdle) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
                    else alarms.setExact(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
                    return
                } catch (_: SecurityException) { /* Permission can be revoked between check and use. */ }
            }
            if (wakeIdle) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
            else alarms.set(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
        schedule(7310, next, true)
        schedule(7311, warning, false)

        if (Build.VERSION.SDK_INT >= 33 && policy?.schedules.orEmpty().any { it.enabled }) runCatching {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) dpm.setPermissionGrantState(
                ComponentName(context, com.mdmesh.agent.admin.AdminReceiver::class.java), context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS, android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lịch nghỉ · trạng thái", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(WARNING_CHANNEL, "Lịch nghỉ · nhắc trước", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val prefs = context.getSharedPreferences("rest_schedule_notifications", Context.MODE_PRIVATE)
        val active = occurrences.filter { now >= it.start && now < it.end }
        val intent = PendingIntent.getActivity(context, 7310, Intent(context, RestScheduleActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun notification(channel: String, title: String, body: String, ongoing: Boolean) {
            runCatching {
                val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channel) else Notification.Builder(context)
                builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(intent)
                    .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                if (channel == CHANNEL) builder.setSound(null).setVibrate(null)
                nm.notify(if (ongoing) STATUS_ID else ALERT_ID, builder.build())
            }
        }
        if (active.isEmpty()) nm.cancel(STATUS_ID)
        else {
            nm.cancel(ALERT_ID)
            val text = active.joinToString("\n") { o ->
                val scope = if (o.schedule.scope == "ALL") "Các ứng dụng" else "${o.schedule.packages.size} ứng dụng đã chọn"
                "$scope tạm dừng theo lịch ${o.schedule.name} đến ${o.end.atZone(RestScheduleEngine.zone(policy!!)).format(DateTimeFormatter.ofPattern("HH:mm dd/MM"))}. Ngoại lệ và các giới hạn khác vẫn áp dụng."
            }
            notification(CHANNEL, "Đang trong giờ nghỉ", text, true)
        }
        val impending = occurrences.filter { it.schedule.warningMinutes > 0 && now < it.start && now >= it.start.minusSeconds(it.schedule.warningMinutes * 60L) }
        val warningKeys = impending.map { "${it.schedule.id}:${it.start.toEpochMilli()}" }.toSet()
        val sent = prefs.getStringSet("warned", emptySet()).orEmpty()
        if (impending.isNotEmpty() && active.isEmpty()) {
            val first = impending.first()
            val minutes = ((first.start.toEpochMilli() - nowMillis + 59999) / 60000).coerceAtLeast(1)
            val at = first.start.atZone(RestScheduleEngine.zone(policy!!))
            val night = at.hour >= 21 || at.hour < 7
            notification(if (night || warningKeys.all { it in sent }) CHANNEL else WARNING_CHANNEL,
                "Sắp đến giờ nghỉ: ${first.schedule.name}",
                "Còn $minutes phút, bắt đầu lúc ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}. Bạn hãy lưu công việc. Chạm để xem các app bị hạn chế.", false)
        } else nm.cancel(ALERT_ID)
        prefs.edit().putStringSet("warned", (sent + warningKeys).filter { key -> occurrences.any { key == "${it.schedule.id}:${it.start.toEpochMilli()}" } }.toSet()).apply()
    }
}
