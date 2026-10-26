package com.agentsanywhere.app.ui.screens.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.model.AgentDevice

@Composable
internal fun HomeDeviceSelector(devices: List<AgentDevice>, selectedId: String?, onSelect: (String?) -> Unit) {
    if (devices.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.home_project_filter_all_devices)) })
        devices.sortedBy { it.name }.forEach { device ->
            FilterChip(selected = selectedId == device.id, onClick = { onSelect(device.id) }, label = { Text(device.name) })
        }
    }
}
