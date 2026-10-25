package com.mdmesh.core.usage

import com.mdmesh.proto.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RestScheduleEngineTest {
    private fun sleep(days: List<Int> = (1..7).toList()) = AppRestSchedule("sleep", "Giờ ngủ", days = days, from = "22:00", to = "06:00")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()
    @Test fun overnightUsesStartDayAndExclusiveEnd() {
        val p = AppUsagePolicy("Asia/Ho_Chi_Minh", schedules = listOf(sleep(listOf(1))))
        assertEquals(1, RestScheduleEngine.active(p, at("2026-10-19T22:00:00")).size)
        assertEquals(1, RestScheduleEngine.active(p, at("2026-10-20T05:59:00")).size)
        assertTrue(RestScheduleEngine.active(p, at("2026-10-20T06:00:00")).isEmpty())
        assertTrue(RestScheduleEngine.active(p, at("2026-10-19T05:00:00")).isEmpty())
    }
    @Test fun overlapsDoNotUnlockEarlyAndExceptionsAreLocal() {
        val s = sleep().copy(exceptions = listOf("com.phone"))
        val other = AppRestSchedule("study", "Học", scope = "SELECTED", packages = listOf("com.game", "com.phone"), days = (1..7).toList(), from = "05:00", to = "07:00")
        val p = AppUsagePolicy("Asia/Ho_Chi_Minh", schedules = listOf(s, other))
        assertFalse(RestScheduleEngine.targets(s, "com.phone"))
        assertTrue(RestScheduleEngine.targets(other, "com.phone"))
        assertEquals(at("2026-10-20T07:00:00"), RestScheduleEngine.availableAt(p, "com.game", at("2026-10-20T05:30:00")))
        assertEquals(at("2026-10-20T06:00:00"), RestScheduleEngine.availableAt(p, "com.book", at("2026-10-20T05:30:00")))
    }
    @Test fun releaseHonorsPerAppWindows() {
        val rule = AppUsageRule(packageName = "com.game", allowedWindows = listOf(AppUsageWindow((1..7).toList(), "08:00", "18:00")))
        val p = AppUsagePolicy("Asia/Ho_Chi_Minh", rules = listOf(rule), schedules = listOf(sleep()))
        assertEquals(at("2026-10-20T08:00:00"), RestScheduleEngine.availableAt(p, "com.game", at("2026-10-20T05:00:00")))
    }
    @Test fun disabledAndEmptyPoliciesHaveNoRestBlocks() {
        val p = AppUsagePolicy(schedules = listOf(sleep().copy(enabled = false)))
        assertTrue(RestScheduleEngine.active(p, Instant.now()).isEmpty())
        assertTrue(RestScheduleEngine.active(AppUsagePolicy(), Instant.now()).isEmpty())
    }
    @Test fun legacyAllowedWindowsAlsoHandleStartDay() {
        val w = AppUsageWindow(listOf(1), "22:00", "06:00")
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        assertTrue(RestScheduleEngine.inWindow(w, at("2026-10-20T03:00:00").atZone(zone)))
        assertFalse(RestScheduleEngine.inWindow(w, at("2026-10-19T03:00:00").atZone(zone)))
    }
    @Test fun scheduleEnforcesWithoutUsageAccessOrFirstUsageEvent() {
        val p = AppUsagePolicy("Asia/Ho_Chi_Minh", schedules = listOf(sleep()))
        val decision = RestScheduleEngine.decision(p, "com.newapp", at("2026-10-20T03:00:00"), 0, false)
        assertTrue(decision.blocked)
        assertFalse(decision.reached)
    }
    @Test fun quotaAndSchedulesNeverUnlockEachOther() {
        val p = AppUsagePolicy("Asia/Ho_Chi_Minh", rules = listOf(AppUsageRule(packageName = "com.game", dailyLimitMinutes = 10)), schedules = listOf(sleep()))
        assertTrue(RestScheduleEngine.decision(p, "com.game", at("2026-10-20T06:00:00"), 600_000, true).blocked)
        assertTrue(RestScheduleEngine.decision(p.copy(overrides = listOf(AppUsageGrant("com.game", 60, Long.MAX_VALUE))), "com.game", at("2026-10-20T03:00:00"), 600_000, true).blocked)
        assertFalse(RestScheduleEngine.decision(p, "com.game", at("2026-10-20T08:00:00"), 0, true).blocked)
    }
    @Test fun continuousWeeklyBlockHasNoFalseRelease() {
        val p = AppUsagePolicy("Asia/Ho_Chi_Minh", schedules = listOf(sleep(), sleep().copy(id = "day", from = "06:00", to = "22:00")))
        assertNull(RestScheduleEngine.availableAt(p, "com.game", at("2026-10-20T03:00:00")))
    }
    @Test fun configurationAppBanCoversMidnightNoonAndEntireWeekWithoutUsageAccess() {
        val schedules=listOf("00:00" to "12:00", "12:00" to "00:00").mapIndexed { index, window ->
            AppRestSchedule("app-block:test:$index", "Ban", scope="SELECTED", packages=listOf("com.game"), days=(1..7).toList(), from=window.first, to=window.second)
        }
        val p=AppUsagePolicy("Asia/Ho_Chi_Minh", schedules=schedules)
        for(day in 19..25) for(time in listOf("00:00:00","11:59:59","12:00:00","23:59:59")) {
            val now=at("2026-10-${day}T$time")
            assertTrue(RestScheduleEngine.decision(p,"com.game",now,0,false).blocked)
            assertFalse(RestScheduleEngine.decision(p,"com.other",now,0,false).blocked)
        }
        assertNull(RestScheduleEngine.availableAt(p,"com.game",at("2026-10-20T12:00:00")))
        assertFalse(RestScheduleEngine.decision(p.copy(schedules=emptyList()),"com.game",at("2026-10-20T12:00:00"),0,false).blocked)
    }
}
