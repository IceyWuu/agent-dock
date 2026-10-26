package com.agentsanywhere.app.ui.screens.documents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.agentsanywhere.app.feature.files.WorkspaceFileKind

/** Hoisted above split/full-screen layout branches so changing layout never closes a document. */
internal class WorkspaceBrowserState(path: String = ".", selected: String? = null, filter: WorkspaceFileKind = WorkspaceFileKind.All, query: String = "") {
    val path = mutableStateOf(path)
    val selected = mutableStateOf(selected)
    val filter = mutableStateOf(filter)
    val query = mutableStateOf(query)
}

@Composable
internal fun rememberWorkspaceBrowserState(device: String, root: String): WorkspaceBrowserState =
    rememberSaveable(device, root, saver = listSaver(
        save = { listOf(it.path.value, it.selected.value.orEmpty(), it.filter.value.name, it.query.value) },
        restore = { WorkspaceBrowserState(it[0], it[1].ifBlank { null }, WorkspaceFileKind.valueOf(it[2]), it[3]) },
    )) { WorkspaceBrowserState() }
