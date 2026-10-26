package com.agentsanywhere.app.feature.sessions

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val activityClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val activityDateFormat = DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT)

/** Calendar-day comparison in the phone's timezone, independent of its 12/24-hour preference. */
fun sessionActivityTime(value: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val updated = Instant.parse(value).atZone(zone)
        val format = if (updated.toLocalDate() == now.atZone(zone).toLocalDate()) activityClockFormat else activityDateFormat
        format.format(updated)
    }.getOrNull()
}
