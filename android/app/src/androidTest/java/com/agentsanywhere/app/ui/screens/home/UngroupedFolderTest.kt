package com.agentsanywhere.app.ui.screens.home

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.feature.sessions.*
import com.agentsanywhere.app.model.*
import com.agentsanywhere.app.ui.designsystem.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UngroupedFolderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun project(id: String, path: String, device: String = "a") = AgentProject(id, "u", device,
        workspaceProjectName(path), path, false, null, 1, null, "", "")
    private fun session(id: String, projectId: String?, device: String = "a", archived: Boolean = false, pinned: Boolean = false) = AgentSession(
        id, device, projectId, "Laptop", id, "", null, "workspace", "codex", "Codex", SessionStatus.Idle, "", "", "",
        pinned, archived, false, 0, false, true, false, "2026-10-26T01:00:00Z", 0,
    )
    @Test fun looseChatsAppearInOneFolderAndRespectDeviceSelection() = checkFolder(false, 1f)
    @Test fun folderWorksInDarkThemeAndLargeText() = checkFolder(true, 2f)
    private fun checkFolder(dark: Boolean, scale: Float) {
        val autoA = project("auto-a", "/Users/a/Documents/Codex/2026-10-25/bang")
        val autoB = project("auto-b", "/Users/b/Documents/Codex/2026-10-25/new-chat", "b")
        val normal = project("real", "/Projects/正常项目")
        val projects = listOf(autoA, autoB, normal)
        val chats = listOf(session("设备甲的散聊", "auto-a"), session("设备乙的散聊", "auto-b", "b"),
            session("没有项目的对话", null), session("正常项目中的对话", "real"), session("已归档的散聊", "auto-a", archived = true),
            session("置顶对话", "auto-a", pinned = true))
        val devices = listOf(AgentDevice("a", "设备甲", online = true), AgentDevice("b", "设备乙", online = true))
        var opened: AgentSession? = null
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.setContent {
            val context = LocalContext.current
            val localized = remember(context) { context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
            val density = LocalDensity.current
            val storage = remember { context.getSharedPreferences("ungrouped-fixture-${System.nanoTime()}", Context.MODE_PRIVATE) }
            val prefs = remember { HomeProjectPreferences(storage, "fixture") }
            val filter = ProjectDeviceAgentFilter(connectorId = prefs.selectedDeviceId)
            val grouped = ungroupedProjectSidebar(projects, chats, prefs.sessionStatus, filter)
            CompositionLocalProvider(LocalContext provides localized, LocalDensity provides Density(density.density, scale)) {
                AgentsAnywhereTheme(if(dark) AAAppearanceMode.Dark else AAAppearanceMode.Light) {
                    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = LocalAAColors.current.canvas) {
                        Column {
                            HomeDeviceSelector(devices, prefs.selectedDeviceId, prefs::selectDevice)
                            Box(Modifier.weight(1f)) {
                                HomeProjectList(
                                    projects = grouped.projects.filter { projectHasVisibleSessions(it, chats, prefs.sessionStatus, filter) },
                                    ungroupedSessions = grouped.sessions, ungroupedLoading = false, ungroupedError = false, onRetryUngrouped = {},
                                    devices = devices, agentRuntimes = listOf("codex"), deviceAgentFilter = filter, hasProjectsInOtherStatuses = true,
                                    allSessions = chats, projectPreferences = prefs, projectSessionStatus = prefs.sessionStatus,
                                    onProjectSessionStatusChange = prefs::selectSessionStatus, projectErrors = emptyMap(), onRetryProject = {}, onCreateProject = {},
                                    pinnedSessions = chats.filter { it.pinned && !it.archived && filter.matches(it) },
                                    sessionsByProject = chats.filter { projectSessionMatchesStatus(it, prefs.sessionStatus) && filter.matches(it) }.groupBy { it.projectId.orEmpty() },
                                    loadingProjectIds = emptySet(), expandedProjectIds = prefs.expandedIds,
                                    onProjectExpandedChange = { p,e -> prefs.setProjectExpanded(p.id,e) }, onProjectMenu = {}, onNewSession = {},
                                    onSessionLongPress = { _,_ -> }, onOpenSession = { opened = it },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("未分组").assertIsDisplayed()
        compose.onNodeWithText("bang").assertDoesNotExist()
        compose.onNodeWithText("new-chat").assertDoesNotExist()
        compose.onNodeWithText("正常项目").assertIsDisplayed()
        compose.onNodeWithText("设备甲的散聊").assertDoesNotExist()
        compose.onNodeWithText("未分组").performClick()
        compose.onNodeWithText("设备甲的散聊").assertIsDisplayed()
        compose.onNodeWithText("设备乙的散聊").assertIsDisplayed()
        compose.onNodeWithText("没有项目的对话").assertIsDisplayed()
        compose.onNodeWithText("已归档的散聊").assertDoesNotExist()
        compose.onNodeWithText("设备甲的散聊").performTouchInput { click() }
        compose.runOnIdle { assertEquals("auto-a", opened?.projectId) }
        capture("${if(dark) "dark-large" else "light"}-all")
        compose.onNodeWithText("设备甲").performClick()
        compose.onNodeWithText("设备甲的散聊").assertIsDisplayed()
        compose.onNodeWithText("设备乙的散聊").assertDoesNotExist()
        capture("${if(dark) "dark-large" else "light"}-a")
        compose.onNodeWithText("设备乙").performClick()
        compose.onNodeWithText("设备乙的散聊").assertIsDisplayed()
        compose.onNodeWithText("设备甲的散聊").assertDoesNotExist()
        compose.onNodeWithText("正常项目").assertDoesNotExist()
        capture("${if(dark) "dark-large" else "light"}-b")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        compose.runOnUiThread { val view = compose.activity.window.decorView; view.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }; view.invalidate() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ungrouped-qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
