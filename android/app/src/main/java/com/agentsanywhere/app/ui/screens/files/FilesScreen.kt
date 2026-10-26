package com.agentsanywhere.app.ui.screens.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.feature.files.FilesController
import com.agentsanywhere.app.feature.sessions.SessionsState
import com.agentsanywhere.app.feature.sessions.isUngroupedWorkspace
import com.agentsanywhere.app.navigation.AppDestination
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import com.agentsanywhere.app.ui.screens.documents.WorkspaceBrowser
import com.agentsanywhere.app.ui.screens.home.rememberHomeProjectPreferences

@Composable
fun FilesScreen(
    navigate: (AppDestination) -> Unit,
    state: SessionsState,
    controller: FilesController,
    onPairDevice: () -> Unit,
    serverUrl: String,
    userId: String,
) {
    val preferences = rememberHomeProjectPreferences(serverUrl, userId)
    var deviceId by rememberSaveable { mutableStateOf(preferences.selectedDeviceId ?: state.devices.firstOrNull { it.online }?.id) }
    var projectId by rememberSaveable(deviceId) { mutableStateOf(preferences.fileProjectId) }
    var deviceMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    val device = state.devices.firstOrNull { it.id == deviceId }
    val projects = state.projects.filter { it.connectorId == deviceId && it.workspacePath.isNotBlank() && !it.isUngroupedWorkspace() }
        .distinctBy { it.workspacePath }.sortedBy { it.name.lowercase() }
    val project = projects.firstOrNull { it.id == projectId }
    BackHandler { navigate(AppDestination.Sessions) }
    LaunchedEffect(state.devices, state.hasLoaded) {
        if (state.hasLoaded && state.devices.none { it.id == deviceId }) deviceId = state.devices.firstOrNull { it.online }?.id
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton({ navigate(AppDestination.Sessions) }) { Text(stringResource(R.string.workspace_conversations)) }
            TextButton({}, enabled = false) { Text(stringResource(R.string.workspace_files), color = LocalAAColors.current.ink) }
            Spacer(Modifier.weight(1f))
            if (state.devices.isEmpty()) TextButton(onPairDevice) { Text(stringResource(R.string.home_pair_new_device)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Box(Modifier.weight(1f)) {
                TextButton({ deviceMenu = true }) { Text(device?.name ?: stringResource(R.string.document_choose_device), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(deviceMenu, { deviceMenu = false }) {
                    state.devices.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = {
                        deviceId = item.id; projectId = null; preferences.selectFileProject(null); preferences.selectDevice(item.id); deviceMenu = false
                    }) }
                }
            }
            Box(Modifier.weight(1f)) {
                TextButton({ projectMenu = true }) { Text(project?.name ?: stringResource(R.string.document_device_files), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(projectMenu, { projectMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.document_device_files)) }, onClick = { projectId = null; preferences.selectFileProject(null); projectMenu = false })
                    projects.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { projectId = item.id; preferences.selectFileProject(item.id); projectMenu = false }) }
                }
            }
        }
        when {
            device == null -> com.agentsanywhere.app.ui.screens.documents.ReaderStatus(stringResource(R.string.files_pair_first))
            !device.online -> com.agentsanywhere.app.ui.screens.documents.ReaderStatus(stringResource(R.string.files_all_devices_offline))
            else -> key(serverUrl, userId, device.id, project?.id) {
                WorkspaceBrowser(device.id, project?.workspacePath ?: "~", device.deviceOs, controller, Modifier.weight(1f))
            }
        }
    }
}
