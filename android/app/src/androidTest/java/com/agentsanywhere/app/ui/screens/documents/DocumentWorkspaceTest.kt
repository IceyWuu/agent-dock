package com.agentsanywhere.app.ui.screens.documents

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.FilesApi
import com.agentsanywhere.app.feature.auth.AuthSessionStore
import com.agentsanywhere.app.feature.files.*
import com.agentsanywhere.app.ui.designsystem.AAAppearanceMode
import com.agentsanywhere.app.ui.designsystem.AgentsAnywhereTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.coroutines.runBlocking

/** Host fixture is opt-in and isolated. It never sends messages or reads production workspaces. */
@RunWith(AndroidJUnit4::class)
class DocumentWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(test: (FilesController) -> Unit) {
        val url = InstrumentationRegistry.getArguments().getString("documentFixture")
        org.junit.Assume.assumeNotNull(url)
        val prefs = context.getSharedPreferences("agents_anywhere_auth", Context.MODE_PRIVATE)
        val original = prefs.all
        prefs.edit().putString("server_url", url).putString("access_token", "isolated-document-test").putString("user_id", "document-test").commit()
        try { test(FilesController(FilesApi(), AuthSessionStore(context), context.cacheDir)) }
        finally {
            val editor = prefs.edit().clear()
            original.forEach { (k,v) -> when(v) {
                is String -> editor.putString(k,v); is Boolean -> editor.putBoolean(k,v)
                is Long -> editor.putLong(k,v); is Int -> editor.putInt(k,v); is Float -> editor.putFloat(k,v)
            } }
            editor.commit()
        }
    }

    @Test fun browserFiltersAndRenderedMarkdownSourceShareReader() = fixture { controller ->
        compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) {
            WorkspaceBrowser("fixture", "/fixture", "macos", controller, Modifier.windowInsetsPadding(WindowInsets.statusBars))
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("report.md").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Markdown", useUnmergedTree = true).performClick()
        compose.onNodeWithText("report.pdf").assertDoesNotExist()
        compose.onNodeWithText("subfolder").assertExists()
        compose.onNodeWithText("report.md").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Rendered report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rendered report").assertIsDisplayed()
        capture("markdown-reading")
        compose.onNodeWithText(context.getString(R.string.document_source)).performClick()
        compose.onNodeWithText("# Rendered report", substring = true).assertExists()
        capture("markdown-source")
        compose.onNodeWithText(context.getString(R.string.document_read)).performClick()
        compose.onNodeWithText("Rendered report").assertIsDisplayed()
    }

    @Test fun streamedPdfPagingZoomAndReadingPositionSurviveReopen() = fixture { controller ->
        val document = runBlocking { controller.readDocument("fixture", "/fixture", "/fixture/report.pdf").getOrThrow() }
        DocumentReadingStore(context).savePosition(document.identity, "pdf", 0)
        var opened by mutableStateOf(true)
        compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Dark) {
            if (opened) DocumentReader(document, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.document_next)).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("140%").assertExists()
        capture("pdf-page-two-zoom")
        compose.runOnIdle { opened = false }
        compose.waitForIdle()
        compose.runOnIdle { opened = true }
        compose.waitUntil(15000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, DocumentReadingStore(context).position(document.identity, "pdf"))
    }

    @Test fun splitResizeAndFullScreenKeepBothPanelsUsable() {
        compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) {
            var pane by remember { mutableIntStateOf(0) }
            ConversationWorkspace(pane, { pane = it }, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { index ->
                if (index == 0) Text("Conversation panel") else Text("Document panel")
            }
        } }
        compose.onNodeWithText(context.getString(R.string.workspace_split)).performClick()
        compose.onNodeWithText("Conversation panel").assertIsDisplayed()
        compose.onNodeWithText("Document panel").assertIsDisplayed()
        val horizontal = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val boundsBefore = compose.onNodeWithText("Document panel").getUnclippedBoundsInRoot()
        val before = if (horizontal) boundsBefore.left else boundsBefore.top
        compose.onNodeWithContentDescription(context.getString(R.string.workspace_resize)).performTouchInput {
            down(center); moveBy(if (horizontal) Offset(-120f, 0f) else Offset(0f, -120f)); up()
        }
        val boundsAfter = compose.onNodeWithText("Document panel").getUnclippedBoundsInRoot()
        val after = if (horizontal) boundsAfter.left else boundsAfter.top
        assertTrue(after < before)
        capture(if (horizontal) "split-landscape-resized" else "split-resized")
        compose.onNodeWithText(context.getString(R.string.workspace_files)).performClick()
        compose.onNodeWithText("Document panel").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.workspace_resize)).assertDoesNotExist()
    }

    @Test fun deliveredFileCardOpensPdfDirectlyInSharedReader() = fixture { controller ->
        var path by mutableStateOf<String?>(null)
        compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) {
            if (path == null) WorkspaceFileCard("/fixture/report.pdf", "Final PDF") { path = it }
            else WorkspaceBrowser("fixture", "/fixture", "macos", controller, openFilePath = path)
        } }
        compose.onNodeWithText("report.pdf").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
        capture("delivered-pdf-card")
    }

    @Test fun actualConversationChromeSupportsPortraitSplitAndFileReading() = fixture { files ->
        val auth = AuthSessionStore(context)
        val session = com.agentsanywhere.app.model.AgentSession(
            "fixture", "fixture", "project", "Fixture computer", "Document workspace", "", "/fixture", "workspace", "dsh", "DSH",
            com.agentsanywhere.app.model.SessionStatus.Idle, "", "", "", false, false, false, 0, false, true, false, "", 0,
        )
        val device = com.agentsanywhere.app.model.AgentDevice("fixture", "Fixture computer", "macos", true)
        val controller = com.agentsanywhere.app.feature.sessiondetail.SessionDetailController(com.agentsanywhere.app.api.SessionsApi(), auth)
        val realtime = com.agentsanywhere.app.feature.realtime.SessionRealtimeController(com.agentsanywhere.app.api.RealtimeApi(), auth, "isolated-documents")
        val terminals = com.agentsanywhere.app.feature.terminal.RemoteTerminalPool(com.agentsanywhere.app.feature.terminal.TerminalController(com.agentsanywhere.app.api.TerminalApi(), auth))
        try {
            compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) {
                com.agentsanywhere.app.ui.screens.sessiondetail.SessionDetailScreen(
                    navigate = {}, sessionId = "fixture", initialSession = session, devices = listOf(device), controller = controller,
                    realtimeController = realtime, filesController = files, terminalPool = terminals,
                    composerDraftStore = com.agentsanywhere.app.ui.screens.sessiondetail.SessionComposerDraftStore(),
                )
            } }
            compose.onNodeWithText(context.getString(R.string.workspace_split)).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("workspace-file-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("workspace-file-list").performScrollToNode(hasText("report.md"))
            compose.onNode(hasText("report.md") and hasAnyAncestor(hasTestTag("workspace-file-list"))).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Rendered report").fetchSemanticsNodes().isNotEmpty() }
            capture("actual-conversation-split")
            compose.onNodeWithContentDescription(context.getString(R.string.workspace_resize)).performTouchInput {
                swipe(center, center + Offset(0f, -100f), 400)
            }
            capture("actual-conversation-resized")
            compose.onAllNodesWithText(context.getString(R.string.workspace_files)).onFirst().performClick()
            compose.waitUntil(15000) { runCatching { compose.onNodeWithText("Rendered report").assertIsDisplayed() }.isSuccess }
            capture("actual-conversation-full")
        } finally { terminals.disposeLocal() }
    }

    @Test fun longMarkdownRemembersScrollAcrossReaderAndSourceModes() = fixture { controller ->
        val document = runBlocking { controller.readDocument("fixture", "/fixture", "/fixture/long.md").getOrThrow() }
        val store = DocumentReadingStore(context)
        store.saveSourceMode(document.identity, false)
        store.savePosition(document.identity, "read-index", 0)
        var opened by mutableStateOf(true)
        compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) {
            if (opened) DocumentReader(document, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Rendered report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("document-markdown").performScrollToIndex(400)
        compose.waitForIdle()
        compose.runOnIdle { opened = false }
        compose.waitForIdle()
        assertTrue(store.position(document.identity, "read-index") >= 400)
        compose.runOnIdle { opened = true }
        compose.waitUntil(15000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.document_source)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("document-source").performScrollToIndex(20)
        compose.onNodeWithText(context.getString(R.string.document_read)).performClick()
        capture("long-markdown-restored")
        assertTrue(store.position(document.identity, "read-index") >= 400)
    }

    @Test fun invalidPdfReportsReadableError() = fixture { controller ->
        val document = runBlocking { controller.readDocument("fixture", "/fixture", "/fixture/broken.pdf").getOrThrow() }
        compose.setContent { AgentsAnywhereTheme(AAAppearanceMode.Light) { DocumentReader(document) } }
        compose.waitUntil(15000) { compose.onAllNodesWithText(context.getString(R.string.document_pdf_failed)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(500)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(context.getExternalFilesDir(null), "documents-qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
