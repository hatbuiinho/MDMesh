package com.mdmesh.core.usage

import com.mdmesh.proto.*
import java.time.*

/** Pure calendar logic; no network or usage permission needed. End boundaries are exclusive. */
object RestScheduleEngine {
    data class Occurrence(val schedule: AppRestSchedule, val start: Instant, val end: Instant)
    fun zone(policy: AppUsagePolicy): ZoneId = runCatching { ZoneId.of(policy.timezone) }.getOrDefault(ZoneOffset.UTC)

    fun occurrences(policy: AppUsagePolicy, now: Instant): List<Occurrence> {
        val zone = zone(policy)
        val today = now.atZone(zone).toLocalDate()
        return policy.schedules.filter { it.enabled }.flatMap { s ->
            (-1L..8L).mapNotNull { offset ->
                runCatching {
                    val day = today.plusDays(offset)
                    if (day.dayOfWeek.value !in s.days) return@runCatching null
                    val from = LocalTime.parse(s.from); val to = LocalTime.parse(s.to)
                    if (from == to) return@runCatching null
                    Occurrence(s, day.atTime(from).atZone(zone).toInstant(),
                        day.plusDays(if (to < from) 1 else 0).atTime(to).atZone(zone).toInstant())
                }.getOrNull()
            }
        }.sortedBy { it.start }
    }
    fun active(policy: AppUsagePolicy, now: Instant) = occurrences(policy, now).filter { now >= it.start && now < it.end }
    fun targets(s: AppRestSchedule, pkg: String) = pkg !in s.exceptions && (s.scope == "ALL" || pkg in s.packages)

    fun inWindow(w: AppUsageWindow, now: ZonedDateTime): Boolean = runCatching {
        val from = LocalTime.parse(w.from); val to = LocalTime.parse(w.to); val time = now.toLocalTime()
        when {
            from == to -> false
            from < to -> now.dayOfWeek.value in w.days && time >= from && time < to
            time >= from -> now.dayOfWeek.value in w.days
            time < to -> now.minusDays(1).dayOfWeek.value in w.days
            else -> false
        }
    }.getOrDefault(false)

    data class Decision(val reached: Boolean, val outside: Boolean, val rests: List<Occurrence>, val limitMs: Long?) {
        val blocked: Boolean get() = reached || outside || rests.isNotEmpty()
    }
    fun decision(policy: AppUsagePolicy?, pkg: String, now: Instant, usedMs: Long, usageAccess: Boolean): Decision {
        if (policy == null) return Decision(false, false, emptyList(), null)
        val rule = policy.rules.firstOrNull { it.enabled && it.packageName == pkg }
        val grant = policy.overrides.filter { it.packageName == pkg && it.expiresAt > now.toEpochMilli() }.sumOf { it.extraMinutes.toLong() }
        val limit = rule?.dailyLimitMinutes?.let { (it.toLong() + grant) * 60_000L }
        val outside = rule != null && rule.allowedWindows.isNotEmpty() && rule.allowedWindows.none { inWindow(it, now.atZone(zone(policy))) }
        return Decision(usageAccess && limit != null && usedMs >= limit, outside,
            active(policy, now).filter { targets(it.schedule, pkg) }, limit)
    }

    /** First release after all overlapping rest windows and per-app windows; null if none in a week. */
    fun availableAt(policy: AppUsagePolicy, pkg: String, now: Instant): Instant? {
        val windows = occurrences(policy, now).filter { targets(it.schedule, pkg) }
        val rule = policy.rules.firstOrNull { it.enabled && it.packageName == pkg }
        val horizon = now.plusSeconds(8 * 86400L)
        val allowedStarts = rule?.allowedWindows.orEmpty().flatMapIndexed { index, w ->
            occurrences(policy.copy(schedules = listOf(AppRestSchedule("allowed-$index", "", days = w.days, from = w.from, to = w.to))), now).map { it.start }
        }
        // Availability can begin only now, at a rest end, or at an allowed-window start.
        // Sweep those boundaries instead of polling every minute for every installed app.
        return (listOf(now) + windows.map { it.end } + allowedStarts).asSequence()
            .filter { it >= now && it <= horizon }.distinct().sorted().firstOrNull { at ->
                windows.none { at >= it.start && at < it.end } &&
                    (rule == null || rule.allowedWindows.isEmpty() || rule.allowedWindows.any { inWindow(it, at.atZone(zone(policy))) })
            }

    }
}
