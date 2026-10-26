package com.agentsanywhere.app.feature.sessions

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SessionActivityTimeTest {
    private val hongKong = ZoneId.of("Asia/Hong_Kong")
    private val now = Instant.parse("2026-10-26T07:00:00Z")
    @Test fun todayAlwaysUses24HourClock() {
        assertEquals("14:35", sessionActivityTime("2026-10-26T06:35:23Z", now, hongKong))
        assertEquals("00:05", sessionActivityTime("2026-10-25T16:05:00Z", now, hongKong))
        assertEquals("09:07", sessionActivityTime("2026-10-26T09:07:00+08:00", now, hongKong))
    }
    @Test fun anotherDayUsesMonthAndDayWithoutYear() {
        assertEquals("10/25", sessionActivityTime("2026-10-25T15:59:00Z", now, hongKong))
        assertEquals("01/09", sessionActivityTime("2025-01-09T10:00:00Z", now, hongKong))
    }
    @Test fun midnightChangesTimeToDateEvenWhenOnlyMinutesHaveElapsed() {
        val updated = "2026-10-25T15:59:00Z"
        assertEquals("23:59", sessionActivityTime(updated, Instant.parse("2026-10-25T15:59:30Z"), hongKong))
        assertEquals("10/25", sessionActivityTime(updated, Instant.parse("2026-10-25T16:00:00Z"), hongKong))
    }
    @Test fun dayBoundaryFollowsPhonesTimezone() {
        val updated = "2026-10-25T16:05:00Z"
        assertEquals("00:05", sessionActivityTime(updated, now, hongKong))
        assertEquals("10/25", sessionActivityTime(updated, now, ZoneId.of("UTC")))
    }
    @Test fun missingAndMalformedTimeIsNotInvented() {
        for (value in listOf(null, "", " ", "not-a-date")) assertNull(sessionActivityTime(value, now, hongKong))
    }
}
