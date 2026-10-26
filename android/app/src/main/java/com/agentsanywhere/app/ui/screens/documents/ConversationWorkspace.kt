package com.agentsanywhere.app.ui.screens.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.ui.designsystem.LocalAAColors

/** Both panes retain their saved state when switching between split and full-screen modes. */
@Composable
internal fun ConversationWorkspace(
    selectedPane: Int,
    onPaneSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    var split by rememberSaveable { mutableStateOf(false) }
    var fraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    val holder = rememberSaveableStateHolder()
    val colors = LocalAAColors.current
    val dragLabel = stringResource(R.string.workspace_resize)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton({ split = false; onPaneSelected(0) }) { Text(stringResource(R.string.workspace_conversations)) }
            TextButton({ split = false; onPaneSelected(1) }) { Text(stringResource(R.string.workspace_files)) }
            TextButton({ if (split) { split = false; onPaneSelected(1) } else split = true }) { Text(stringResource(if (split) R.string.workspace_fullscreen else R.string.workspace_split)) }
        }
        if (split) {
            val horizontal = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val extent = with(LocalDensity.current) { if (horizontal) maxWidth.toPx() else maxHeight.toPx() }
                val divider: @Composable () -> Unit = {
                    Box(
                        (if (horizontal) Modifier.width(18.dp).fillMaxHeight() else Modifier.height(22.dp).fillMaxWidth())
                            .semantics { contentDescription = dragLabel }
                            .pointerInput(extent, horizontal) {
                                if (horizontal) detectHorizontalDragGestures { change, drag ->
                                    change.consume()
                                    fraction = (fraction + drag / extent.coerceAtLeast(1f)).coerceIn(0.25f, 0.75f)
                                } else detectVerticalDragGestures { change, drag ->
                                    change.consume()
                                    fraction = (fraction + drag / extent.coerceAtLeast(1f)).coerceIn(0.25f, 0.75f)
                                }
                            }, contentAlignment = Alignment.Center,
                    ) {
                        Box((if (horizontal) Modifier.width(3.dp).height(64.dp) else Modifier.height(3.dp).width(64.dp)).background(colors.muted))
                    }
                }
                if (horizontal) Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(fraction).fillMaxHeight()) { holder.SaveableStateProvider(0) { content(0) } }
                    divider()
                    Box(Modifier.weight(1f - fraction).fillMaxHeight()) { holder.SaveableStateProvider(1) { content(1) } }
                } else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(fraction).fillMaxWidth()) { holder.SaveableStateProvider(0) { content(0) } }
                    divider()
                    Box(Modifier.weight(1f - fraction).fillMaxWidth()) { holder.SaveableStateProvider(1) { content(1) } }
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                holder.SaveableStateProvider(selectedPane) { content(selectedPane) }
            }
        }
    }
}
