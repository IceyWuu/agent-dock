package com.agentsanywhere.app.ui.screens.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class MessageTimestampTest {
    @Test fun utcRecordsUsePhoneTimeIncludingDayRollover() {
        assertEquals("2026-10-26 00:01:02", messageTimestamp("2026-10-25T16:01:02.123456Z", ZoneId.of("Asia/Hong_Kong")))
        assertEquals("2026-10-25 09:01:02", messageTimestamp("2026-10-25T16:01:02Z", ZoneId.of("America/Los_Angeles")))
    }
    @Test fun missingOrInvalidDatesNeverBecomeTheCurrentTime() {
        for (value in listOf(null, "", "null", "not-a-date")) assertNull(messageTimestamp(value))
    }
}
