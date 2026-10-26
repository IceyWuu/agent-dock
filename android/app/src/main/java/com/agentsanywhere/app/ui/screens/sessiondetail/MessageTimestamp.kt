package com.agentsanywhere.app.ui.screens.sessiondetail

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.feature.sessiondetail.TimelineMessage
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun messageTimestamp(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
    value?.takeIf(String::isNotBlank)?.let {
        runCatching {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .withZone(zone).format(Instant.parse(it))
        }.getOrNull()
    }

@Composable
internal fun MessageTimestamp(message: TimelineMessage) {
    val time = messageTimestamp(message.createdAt) ?: stringResource(R.string.session_time_unknown)
    val label = when {
        message.optimistic && message.status == "pending" -> R.string.session_time_sending
        message.status == "failed" && message.author == com.agentsanywhere.app.feature.sessiondetail.MessageAuthor.User -> R.string.session_time_failed
        message.author == com.agentsanywhere.app.feature.sessiondetail.MessageAuthor.User -> R.string.session_time_sent
        else -> R.string.session_time_received
    }
    DisableSelection {
        Text(text = stringResource(label, time), color = LocalAAColors.current.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
