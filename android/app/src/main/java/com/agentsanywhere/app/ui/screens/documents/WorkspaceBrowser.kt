package com.agentsanywhere.app.ui.screens.documents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.feature.files.*
import com.agentsanywhere.app.ui.designsystem.LocalAAColors

@Composable
internal fun WorkspaceBrowser(
    connectorId: String,
    root: String,
    deviceOs: String?,
    controller: FilesController,
    modifier: Modifier = Modifier,
    openFilePath: String? = null,
    onOpenFileRequestConsumed: (String) -> Unit = {},
    navigation: WorkspaceBrowserState = rememberWorkspaceBrowserState(connectorId, root),
) {
    val colors = LocalAAColors.current
    var path by navigation.path
    var selected by navigation.selected
    var filter by navigation.filter
    var query by navigation.query
    var directory by remember(connectorId, root) { mutableStateOf<FilesDirectory?>(null) }
    var document by remember(connectorId, root, selected) { mutableStateOf<WorkspaceDocument?>(null) }
    var error by remember(connectorId, root) { mutableStateOf<String?>(null) }
    var loading by remember(connectorId, root) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var resolvedRoot by remember(connectorId, root) { mutableStateOf(root) }
    var progress by remember(connectorId, root, selected) { mutableStateOf<DocumentLoadProgress?>(null) }
    var forceRefresh by remember { mutableStateOf(false) }
    val failure = stringResource(R.string.document_load_failed)

    LaunchedEffect(openFilePath, connectorId, root) {
        openFilePath?.takeIf { it.isNotBlank() }?.let { selected = it; onOpenFileRequestConsumed(it) }
    }
    LaunchedEffect(connectorId, root, path, revision, selected) {
        if (selected != null) return@LaunchedEffect
        loading = true; error = null
        val request = remoteFileRequest(path, deviceOs, root)
        controller.listFiles(connectorId, request.root, request.path)
            .onSuccess { directory = it; if (path == ".") resolvedRoot = it.path }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = failure }
        loading = false
    }
    LaunchedEffect(connectorId, root, selected, revision) {
        val target = selected ?: return@LaunchedEffect
        document = null; error = null
        progress = null
        val refresh = forceRefresh
        forceRefresh = false
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val request = remoteFileRequest(target, deviceOs, root)
        controller.readDocument(connectorId, request.root, request.path, forceRefresh = refresh, onProgress = {
            if (job?.isActive != false) progress = it
        })
            .onSuccess { document = it }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; error = failure }
    }
    BackHandler(selected != null) { selected = null; error = null }
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        if (selected != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ selected = null; error = null }, Modifier.weight(1f)) {
                    Text("‹ " + selected!!.replace('\\', '/').substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ forceRefresh = true; revision++ }) { Text(stringResource(R.string.document_reload)) }
            }
            when {
                error != null -> ReaderStatus(error!!)
                document == null -> {
                    val current = progress
                    val downloading = current?.stage == DocumentLoadStage.Downloading
                    val fraction = if (downloading && current!!.total > 0) current.received.toFloat() / current.total else null
                    val label = if (downloading) stringResource(R.string.document_download_progress,
                        (current!!.received / 1048576.0).toFloat(), (current.total / 1048576.0).toFloat())
                        else stringResource(R.string.document_checking)
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(label, color = colors.muted)
                            Spacer(Modifier.height(12.dp))
                            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                else -> DocumentReader(document!!, Modifier.weight(1f), onOpenFile = { link ->
                    selected = resolveWorkspaceLink(selected!!, link)
                }, loadImage = { link ->
                    val imagePath = resolveWorkspaceLink(selected!!, link)
                    val request = remoteFileRequest(imagePath, deviceOs, root)
                    controller.readDocument(connectorId, request.root, request.path).getOrNull()?.file
                })
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkspaceFileKind.entries.forEach { kind ->
                    FilterChip(filter == kind, { filter = kind }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.subtle, selectedLabelColor = colors.ink), label = { Text(stringResource(fileFilterLabel(kind))) })
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                singleLine = true, placeholder = { Text(stringResource(R.string.document_search)) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ path = remoteParentPath(directory?.path ?: path, deviceOs) ?: "." }, enabled = path != "." && directory?.path != resolvedRoot) {
                    Text(stringResource(R.string.document_parent))
                }
                TextButton({ revision++ }) { Text(stringResource(R.string.document_reload)) }
            }
            Text(directory?.path ?: root, Modifier.padding(horizontal = 16.dp), color = colors.muted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            when {
                loading -> ReaderStatus(stringResource(R.string.files_loading))
                error != null -> ReaderStatus(error!!)
                else -> {
                    val entries = filterWorkspaceFiles(directory?.entries.orEmpty(), filter, query)
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("workspace-file-list")) {
                        if (entries.isEmpty()) item { Text(stringResource(R.string.document_no_matches), Modifier.padding(20.dp), color = colors.muted) }
                        items(entries, key = { it.path }) { entry ->
                            Row(Modifier.fillMaxWidth().clickable {
                                if (entry.isDirectory) path = entry.path else selected = entry.path
                                error = null
                            }.padding(horizontal = 16.dp, vertical = 15.dp)) {
                                Text(if (entry.isDirectory) "▸ " else "   ", color = colors.muted)
                                Text(entry.name, Modifier.weight(1f), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (!entry.isDirectory) Text(entry.name.substringAfterLast('.', "").uppercase(), color = colors.muted)
                            }
                            HorizontalDivider(color = colors.border)
                        }
                    }
                }
            }
        }
    }
}

internal fun fileFilterLabel(kind: WorkspaceFileKind): Int = when (kind) {
    WorkspaceFileKind.All -> R.string.document_filter_all
    WorkspaceFileKind.Markdown -> R.string.document_filter_markdown
    WorkspaceFileKind.Pdf -> R.string.document_filter_pdf
    WorkspaceFileKind.Image -> R.string.document_filter_images
    WorkspaceFileKind.Other -> R.string.document_filter_other
}
