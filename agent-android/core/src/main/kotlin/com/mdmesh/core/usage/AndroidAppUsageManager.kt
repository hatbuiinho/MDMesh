package com.mdmesh.core.usage

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import com.mdmesh.proto.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.*

/** Offline-authoritative evaluator. Policy and reached-at markers survive process death/reboot. */
class AndroidAppUsageManager(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppUsageManager {
    private val prefs = context.getSharedPreferences("app_usage_policy", Context.MODE_PRIVATE)
    private val reportPrefs = context.getSharedPreferences("app_usage_report", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun apply(policy: AppUsagePolicy?): String {
        if (policy == null) {
            val old = load()
            old?.rules?.forEach { setSuspended(it.packageName, false) }
            prefs.edit().clear().apply()
            return "applied"
        }
        val previous = load()
        previous?.rules?.filter { old -> policy.rules.none { it.packageName == old.packageName } }
            ?.forEach { setSuspended(it.packageName, false) }
        prefs.edit().putString(KEY_POLICY, json.encodeToString(policy)).apply()
        if (!dpm.isDeviceOwnerApp(context.packageName) || !hasUsageAccess()) return "unsupported"
        return runCatching { evaluateAndReport(); "applied" }.getOrElse { "failed: ${it.message ?: "usage evaluation"}" }
    }

    override fun evaluateAndReport(): List<AppUsageDailyReport> {
        val policy = load()
        val now = clock()
        val zone = runCatching { ZoneId.of(policy?.timezone ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneOffset.UTC)
        if (!hasUsageAccess()) return policy?.rules.orEmpty().map { AppUsageDailyReport(it.packageName, today(policy!!).toString(), 0, status = "usage_access_missing") }
        val instant = Instant.ofEpochMilli(now); val date = instant.atZone(zone).toLocalDate()
        val usage = updateUsage(now, zone)
        val rules = policy?.rules.orEmpty().filter { it.enabled }.associateBy { it.packageName }
        val reports = usage.entries.map { (key, used) ->
            val split = key.indexOf('|')
            val usageDate = key.substring(0, split)
            val packageName = key.substring(split + 1)
            val rule = rules[packageName]
            if (rule == null) {
                return@map AppUsageDailyReport(packageName, usageDate, used, status = "observed")
            }
            if (usageDate != date.toString()) {
                val reachedAt = prefs.getLong("reached:$usageDate:$packageName", 0).takeIf { it > 0 }
                return@map AppUsageDailyReport(packageName, usageDate, used, reachedAt,
                    if (reachedAt != null) "limit_reached" else "allowed")
            }
            val grant = policy!!.overrides.filter { it.packageName == rule.packageName && it.expiresAt > now }.sumOf { it.extraMinutes }
            val outside = rule.allowedWindows.isNotEmpty() && rule.allowedWindows.none { inWindow(it, instant.atZone(zone)) }
            val limit = rule.dailyLimitMinutes?.plus(grant)?.times(60_000L)
            val reached = limit != null && used >= limit
            if (!reached && limit != null && limit - used <= rule.warningMinutes * 60_000L) warn(rule.packageName, (limit - used).coerceAtLeast(0) / 60_000L)
            val status = when { outside -> "outside_schedule"; reached -> "limit_reached"; else -> "allowed" }
            val suspend = outside || reached
            val applied = setSuspended(rule.packageName, suspend)
            val markerKey = "reached:${date}:${rule.packageName}"
            if (reached && !prefs.contains(markerKey)) prefs.edit().putLong(markerKey, now).apply()
            if (!reached) prefs.edit().remove(markerKey).apply()
            AppUsageDailyReport(rule.packageName, usageDate, used,
                prefs.getLong(markerKey, 0).takeIf { it > 0 }, if (applied) status else "enforcement_failed")
        }
        // Keep policy apps visible even before their first foreground event.
        val seenToday = reports.asSequence().filter { it.usageDate == date.toString() }.map { it.packageName }.toSet()
        val emptyPolicyRows = rules.values.filter { it.packageName !in seenToday }.map {
            AppUsageDailyReport(it.packageName, date.toString(), 0, status = "allowed")
        }
        // The server bounds check-ins to 200 usage rows. Prioritize configured apps, then the
        // most-used packages, while retaining yesterday so the final total survives midnight.
        return (reports + emptyPolicyRows).sortedWith(
            compareByDescending<AppUsageDailyReport> { it.packageName in rules }
                .thenByDescending { it.usageDate }
                .thenByDescending { it.foregroundMs }
        ).take(MAX_REPORT_ROWS)
    }

    private fun inWindow(w: AppUsageWindow, now: ZonedDateTime): Boolean {
        if (now.dayOfWeek.value !in w.days) return false
        val from = runCatching { LocalTime.parse(w.from) }.getOrNull() ?: return false
        val to = runCatching { LocalTime.parse(w.to) }.getOrNull() ?: return false
        val time = now.toLocalTime()
        return if (from <= to) time >= from && time < to else time >= from || time < to
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
        return runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), suspended).isEmpty() }.getOrDefault(false)
    }
    private fun isProtected(pkg: String): Boolean {
        if (pkg == context.packageName) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return pkg == launcher
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
