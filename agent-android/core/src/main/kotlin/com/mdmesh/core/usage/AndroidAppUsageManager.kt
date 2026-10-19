package com.mdmesh.core.usage

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import com.mdmesh.policy.PackageSuspensionManager
import com.mdmesh.policy.SuspensionReason
import com.mdmesh.proto.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.*

/** Offline-authoritative evaluator. Policy and reached-at markers survive process death/reboot. */
class AndroidAppUsageManager(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val suspensionManager: PackageSuspensionManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onEvaluated: (AppUsagePolicy?, Long) -> Unit = { _, _ -> },
) : AppUsageManager {
    private val prefs = context.getSharedPreferences("app_usage_policy", Context.MODE_PRIVATE)
    private val reportPrefs = context.getSharedPreferences("app_usage_report", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun currentPolicy(): AppUsagePolicy? = load()
    override fun blockedReason(packageName: String): String? = prefs.getString("blocked:$packageName", null)

    @Synchronized
    override fun apply(policy: AppUsagePolicy?): String {
        if (!prefs.contains("managed")) {
            prefs.edit().putStringSet("managed", load()?.rules.orEmpty().map { it.packageName }.toSet()).commit()
        }
        if (policy == null) prefs.edit().remove(KEY_POLICY).commit()
        else prefs.edit().putString(KEY_POLICY, json.encodeToString(policy)).commit()
        return runCatching {
            evaluateAndReport()
            if (policy != null && !dpm.isDeviceOwnerApp(context.packageName)) "unsupported"
            else if (prefs.getBoolean("enforcement_failed", false)) "failed: some packages could not be updated"
            else "applied"
        }.getOrElse { "failed: ${it.message ?: "usage evaluation"}" }
    }

    @Synchronized
    override fun evaluateAndReport(): List<AppUsageDailyReport> {
        val policy = load()
        val now = clock(); val instant = Instant.ofEpochMilli(now)
        val zone = policy?.let(RestScheduleEngine::zone) ?: ZoneOffset.UTC
        val date = instant.atZone(zone).toLocalDate().toString()
        val access = hasUsageAccess()
        val usage = if (access) updateUsage(now, zone) else emptyMap()
        val rules = policy?.rules.orEmpty().filter { it.enabled }.associateBy { it.packageName }
        val active = policy?.let { RestScheduleEngine.active(it, instant) }.orEmpty()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchable = context.packageManager.queryIntentActivities(launcherIntent, 0).map { it.activityInfo.packageName }
        val managed = prefs.getStringSet("managed", null)?.toSet() ?: policy?.rules.orEmpty().map { it.packageName }.toSet()
        val targets = (rules.keys + active.flatMap { if (it.schedule.scope == "ALL") launchable else it.schedule.packages } + managed).toSet()
        val nextManaged = mutableSetOf<String>()
        val currentReports = mutableListOf<AppUsageDailyReport>()
        var failed = false
        for (pkg in targets) {
            val rule = rules[pkg]
            val used = usage["$date|$pkg"] ?: 0L
            val decision = RestScheduleEngine.decision(policy, pkg, instant, used, access)
            val limit = decision.limitMs
            val reached = decision.reached
            val outside = decision.outside
            val rests = decision.rests
            val mustBlock = decision.blocked
            val protected = isProtected(pkg)
            val reason = when {
                reached -> "Đã hết thời gian sử dụng hôm nay."
                rests.isNotEmpty() || outside -> {
                    val until = policy?.let { RestScheduleEngine.availableAt(it, pkg, instant) }
                    val label = rests.joinToString(", ") { it.schedule.name }.ifEmpty { "Ngoài giờ được phép" }
                    label + if (until != null) " · Có thể dùng lại: " + until.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM")) else " · Chưa có giờ mở lại trong tuần."
                }
                else -> null
            }
            // Own every suspension we assert until a successful release, including failed attempts.
            if (mustBlock && !protected) nextManaged.add(pkg)
            val installed = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            val needsChange = installed && ((mustBlock && !protected) || pkg in managed)
            if (!installed) nextManaged.remove(pkg)
            // Journal ownership before the OS call so a process death cannot orphan a suspension.
            if (needsChange && mustBlock && !protected) prefs.edit().putStringSet("managed", managed + nextManaged).commit()
            val applied = !needsChange || setSuspended(pkg, mustBlock && !protected)
            if (!applied) { failed = true; nextManaged.add(pkg) }
            prefs.edit().apply {
                if (reason != null && !protected && installed) putString("blocked:$pkg", reason)
                else if (!applied) putString("blocked:$pkg", "Chưa thể mở lại ứng dụng. Vui lòng liên hệ quản trị viên.")
                else remove("blocked:$pkg")
            }.apply()
            if (!reached && access && limit != null && used < limit && limit - used <= (rule?.warningMinutes ?: 0) * 60_000L) {
                runCatching { warn(pkg, (limit - used) / 60_000L) }
            }
            val marker = "reached:$date:$pkg"
            if (reached && !prefs.contains(marker)) prefs.edit().putLong(marker, now).apply()
            val status = when {
                !applied -> "enforcement_failed"
                mustBlock && protected -> "protected_app"
                rests.isNotEmpty() -> "rest_schedule"
                outside -> "outside_schedule"
                reached -> "limit_reached"
                !access && limit != null -> "usage_access_missing"
                else -> "allowed"
            }
            currentReports.add(AppUsageDailyReport(pkg, date, used, prefs.getLong(marker, 0).takeIf { it > 0 }, status))
        }
        prefs.edit().putStringSet("managed", nextManaged).putBoolean("enforcement_failed", failed).commit()
        // Local scheduling/notifications run regardless of network and Usage Access permission.
        onEvaluated(policy, now)
        val seen = currentReports.map { it.packageName }.toSet()
        val observed = usage.mapNotNull { (key, used) ->
            val day = key.substringBefore('|'); val pkg = key.substringAfter('|')
            if (day == date && pkg in seen) null else AppUsageDailyReport(pkg, day, used, status = "observed")
        }
        return (currentReports + observed).sortedWith(compareByDescending<AppUsageDailyReport> { it.packageName in rules || it.status == "rest_schedule" }
            .thenByDescending { it.usageDate }.thenByDescending { it.foregroundMs }).take(MAX_REPORT_ROWS)
    }

    /** Incremental event fold. The cursor, totals and open sessions are committed together, so a
     * failed network request can safely resend the same idempotent daily totals without rescanning. */
    private fun updateUsage(end: Long, zone: ZoneId): Map<String, Long> {
        val cache = reportPrefs.getString(KEY_REPORT_CACHE, null)
            ?.let { runCatching { json.decodeFromString<UsageCache>(it) }.getOrNull() } ?: UsageCache()
        val todayStart = Instant.ofEpochMilli(end).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val start = cache.cursor.takeIf { it in 1 until end }?.plus(1) ?: todayStart
        val active = cache.active.toMutableMap()
        val totals = cache.totals.toMutableMap()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start, end)
        if (events == null) return snapshotTotals(totals, active, end, zone)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e); val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // A normal handset has one resumed foreground app. Closing a missing PAUSE
                    // here prevents a killed/misbehaving package from accruing usage forever.
                    active.filterKeys { it != pkg }.toMap().forEach { (other, began) ->
                        addDuration(totals, other, began, e.timeStamp, zone)
                        active.remove(other)
                    }
                    active.putIfAbsent(pkg, e.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    active.remove(pkg)?.let { began -> addDuration(totals, pkg, began, e.timeStamp, zone) }
            }
        }
        val oldest = Instant.ofEpochMilli(end).atZone(zone).toLocalDate().minusDays(1).toString()
        totals.keys.removeAll { it.substringBefore('|') < oldest }
        val validActive = active.filterValues { it in (end - MAX_OPEN_SESSION_MS)..end }
        val updated = UsageCache(end, totals, validActive)
        reportPrefs.edit().putString(KEY_REPORT_CACHE, json.encodeToString(updated)).apply()
        return snapshotTotals(totals, validActive, end, zone)
    }

    private fun snapshotTotals(totals: Map<String, Long>, active: Map<String, Long>, end: Long, zone: ZoneId): Map<String, Long> =
        totals.toMutableMap().also { out -> active.forEach { (pkg, began) -> addDuration(out, pkg, began, end, zone) } }

    private fun addDuration(totals: MutableMap<String, Long>, pkg: String, from: Long, to: Long, zone: ZoneId) {
        var cursor = from.coerceAtMost(to)
        while (cursor < to) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val boundary = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli().coerceAtMost(to)
            val key = "$day|$pkg"
            totals[key] = (totals[key] ?: 0) + (boundary - cursor).coerceAtLeast(0)
            cursor = boundary
        }
    }
    private fun warn(pkg: String, minutes: Long) {
        val key = "warn:${today(load() ?: return)}:$pkg"; if (prefs.getBoolean(key, false)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "App time limits", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        val pending = launch?.let { PendingIntent.getActivity(context, pkg.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, CHANNEL) else android.app.Notification.Builder(context)
        notification.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("App time almost used")
            .setContentText("$pkg has $minutes minute${if (minutes == 1L) "" else "s"} remaining").setAutoCancel(true)
        pending?.let { notification.setContentIntent(it) }; nm.notify(pkg.hashCode(), notification.build()); prefs.edit().putBoolean(key, true).apply()
    }
    private fun setSuspended(pkg: String, suspended: Boolean): Boolean {
        if (isProtected(pkg)) return !suspended
        return suspensionManager.setReason(pkg, SuspensionReason.APP_USAGE, suspended)
    }
    private fun isProtected(pkg: String): Boolean {
        if (pkg == context.packageName) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (pkg == launcher || pkg == "android" || pkg == "com.android.systemui") return true
        val dialer = context.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage
        return pkg == dialer || dpm.activeAdmins.orEmpty().any { it.packageName == pkg }
    }
    private fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    private fun load(): AppUsagePolicy? = prefs.getString(KEY_POLICY, null)?.let { runCatching { json.decodeFromString<AppUsagePolicy>(it) }.getOrNull() }
    private fun today(p: AppUsagePolicy) = Instant.ofEpochMilli(clock()).atZone(runCatching { ZoneId.of(p.timezone) }.getOrDefault(ZoneOffset.UTC)).toLocalDate()
    @Serializable
    private data class UsageCache(
        val cursor: Long = 0,
        val totals: Map<String, Long> = emptyMap(),
        val active: Map<String, Long> = emptyMap(),
    )

    companion object {
        private const val KEY_POLICY = "policy"
        private const val KEY_REPORT_CACHE = "cache_v1"
        private const val CHANNEL = "app_usage_limits"
        private const val MAX_REPORT_ROWS = 200
        private const val MAX_OPEN_SESSION_MS = 24L * 60L * 60L * 1000L
    }
}
