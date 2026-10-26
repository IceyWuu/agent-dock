package com.agentsanywhere.app.ui.screens.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentsanywhere.app.ui.designsystem.LocalAAColors

@Composable
internal fun WorkspaceFileCard(path: String, description: String, onOpen: (String) -> Unit) {
    val colors = LocalAAColors.current
    val name = path.replace('\\', '/').substringAfterLast('/')
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp))
        .background(colors.subtle).clickable { onOpen(path) }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name.substringAfterLast('.', "FILE").uppercase(), color = colors.muted)
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) Text(description, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = colors.muted)
    }
}
