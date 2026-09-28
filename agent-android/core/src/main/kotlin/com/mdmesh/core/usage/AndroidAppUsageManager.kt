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
        val policy = load() ?: return emptyList()
        if (!hasUsageAccess()) return policy.rules.map { AppUsageDailyReport(it.packageName, today(policy).toString(), 0, status = "usage_access_missing") }
        val now = clock(); val zone = runCatching { ZoneId.of(policy.timezone) }.getOrDefault(ZoneOffset.UTC)
        val instant = Instant.ofEpochMilli(now); val date = instant.atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val usage = exactUsage(start, now)
        return policy.rules.filter { it.enabled }.map { rule ->
            val used = usage[rule.packageName] ?: 0L
            val grant = policy.overrides.filter { it.packageName == rule.packageName && it.expiresAt > now }.sumOf { it.extraMinutes }
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
            AppUsageDailyReport(rule.packageName, date.toString(), used,
                prefs.getLong(markerKey, 0).takeIf { it > 0 }, if (applied) status else "enforcement_failed")
        }
    }

    private fun inWindow(w: AppUsageWindow, now: ZonedDateTime): Boolean {
        if (now.dayOfWeek.value !in w.days) return false
        val from = runCatching { LocalTime.parse(w.from) }.getOrNull() ?: return false
        val to = runCatching { LocalTime.parse(w.to) }.getOrNull() ?: return false
        val time = now.toLocalTime()
        return if (from <= to) time >= from && time < to else time >= from || time < to
    }
    private fun exactUsage(start: Long, end: Long): Map<String, Long> {
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start, end) ?: return emptyMap()
        val active = mutableMapOf<String, Long>(); val totals = mutableMapOf<String, Long>(); val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e); val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> active.putIfAbsent(pkg, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> active.remove(pkg)?.let { began -> totals[pkg] = (totals[pkg] ?: 0) + (e.timeStamp - began).coerceAtLeast(0) }
            }
        }
        active.forEach { (pkg, began) -> totals[pkg] = (totals[pkg] ?: 0) + (end - began).coerceAtLeast(0) }
        return totals
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
    companion object { private const val KEY_POLICY = "policy"; private const val CHANNEL = "app_usage_limits" }
}
