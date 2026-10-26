package com.agentsanywhere.app.ui.screens.sessiondetail

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.SessionsApi
import com.agentsanywhere.app.feature.auth.AuthSessionReader
import com.agentsanywhere.app.feature.sessiondetail.*
import com.agentsanywhere.app.ui.designsystem.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.*

/** Exercises production UI and production response transport against a device-local fixture. */
@RunWith(AndroidJUnit4::class)
class RuntimeNoticePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun error() = RuntimeNotice(
        "quota", "interaction", "qa-session", "Codex turn failed", "You've hit your usage limit. Try again at 6:00 PM.",
        "error", "open", "execution_error", RuntimeNoticeBlocking("session", "qa-session"), false,
        1, 1, emptyMap(), emptyList(), mapOf("error" to mapOf("code" to "UsageLimitExceeded")), emptyMap(), null, null, null, null,
    )
    private fun question(multiple: Boolean = false, version: Int = 1, long: Boolean = false): RuntimeNotice {
        val prompt = if (long) "Choose your next step. ".repeat(20) else "Choose your next step"
        val questions = listOf(mapOf("id" to "next", "prompt" to prompt, "header" to "Plan", "multiple" to multiple,
            "allowCustom" to true, "options" to listOf(mapOf("id" to "a", "label" to "Option A"), mapOf("id" to "b", "label" to "Option B"))))
        val action = RuntimeNoticeAction("submit", "Submit", "primary", RuntimeNoticeActionInput(true, null,
            mapOf("component" to "inputRequest", "version" to version, "questions" to questions)), emptyMap())
        return error().copy(noticeId = "question-${System.nanoTime()}", title = "Question", message = null, severity = "info",
            responseRequired = true, interactionType = "input_request", context = emptyMap(), actions = listOf(action))
    }
    @Before fun wake() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_WAKEUP")).use { it.readBytes() }
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    private fun content(notices: List<RuntimeNotice>, allowed: Boolean = true, reason: String? = null, large: Boolean = false,
                        respond: suspend (RuntimeNotice, RuntimeNoticeAction, Map<String, Any?>?) -> Unit = { _,_,_ -> error("Unexpected response") }) {
        compose.setContent {
            val density = LocalDensity.current
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                AgentsAnywhereTheme(AAAppearanceMode.Light) {
                    Surface(Modifier.fillMaxSize().systemBarsPadding(), color = LocalAAColors.current.canvas) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                            BlockingRuntimeNoticeStack(notices, emptySet(), emptyMap(), allowed,
                                onRespond = { n,a,i -> scope.launch { respond(n,a,i) } }, responseUnavailableMessage = reason)
                        }
                    }
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue(committed.await(5, TimeUnit.SECONDS))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var bitmap: Bitmap? = null
        // A committed app frame can precede the system window transition. Require
        // actual card ink on the display, excluding status/navigation bars.
        for (attempt in 0..20) {
            val candidate = checkNotNull(automation.takeScreenshot())
            val pixels = IntArray(candidate.width * candidate.height)
            candidate.getPixels(pixels, 0, candidate.width, 0, 0, candidate.width, candidate.height)
            val ink = (candidate.height / 3 until candidate.height * 9 / 10).sumOf { y ->
                (0 until candidate.width).count { x -> (pixels[y*candidate.width+x] and 0x00FFFFFF) < 0x777777 }
            }
            if (ink > 100) { bitmap = candidate; break }
            candidate.recycle()
            SystemClock.sleep(100)
        }
        val visible = checkNotNull(bitmap) { "The actual display must contain the card" }
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "notice-qa").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { visible.compress(Bitmap.CompressFormat.PNG, 100, it) }
        visible.recycle()
    }
    @Test fun quotaDoesNotOfferAChoice() {
        content(listOf(error()))
        compose.onAllNodesWithText(text(R.string.session_quota_exhausted_title)).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.session_notice_pending_response)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.session_input_request_submit)).assertDoesNotExist()
        compose.onNodeWithText(error().message!!).assertIsDisplayed()
        capture("quota")
    }
    private fun submitToLocalServer(multiple: Boolean = false, custom: Boolean = false, large: Boolean = false) {
        ServerSocket(0).use { socket ->
            socket.soTimeout = 15000
            val request = CompletableFuture<Pair<String, JSONObject>>()
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    socket.accept().use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        var length = 0
                        while (true) {
                            val line = reader.readLine()
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) { val count = reader.read(body, offset, length-offset); check(count > 0); offset += count }
                        val response = "{\"ok\":true}"
                        connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray())
                        request.complete(first to JSONObject(String(body)))
                    }
                } catch (e: Throwable) { request.completeExceptionally(e) }
            }
            try {
                val q = question(multiple, long = large)
                val controller = SessionDetailController(SessionsApi(), object: AuthSessionReader {
                    override fun readServerUrl() = "http://127.0.0.1:${socket.localPort}"
                    override fun readAccessToken() = "isolated-test-token"
                })
                val submitted = CompletableFuture<Unit>()
                content(listOf(error(), q), large = large) { n,a,i ->
                    val result = controller.respondNotice(n.sessionId, n.noticeId, a.actionId, i)
                    result.fold({submitted.complete(Unit)}, {submitted.completeExceptionally(it)})
                }
                // A real question must be reachable even with an older blocking error.
                compose.onNodeWithText(text(R.string.session_notice_pending_response)).assertIsDisplayed()
                if (custom) {
                    compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Use another plan")
                } else {
                    compose.onNodeWithText("Option A").performScrollTo().performClick()
                    compose.onNodeWithText("Option B").performScrollTo().performClick()
                }
                capture(if (custom) "custom-selected" else if (large) "large-font-selected" else if (multiple) "multiple-selected" else "single-selected")
                compose.onNodeWithText(text(R.string.session_input_request_submit)).assertIsDisplayed().performClick()
                submitted.get(15, TimeUnit.SECONDS)
                val (line, body) = request.get(15, TimeUnit.SECONDS)
                assertEquals("POST /api/v2/sessions/qa-session/runtime/notices/${q.noticeId}/respond HTTP/1.1", line)
                assertEquals("submit", body.getString("actionId"))
                val answer = body.getJSONObject("input").getJSONObject("answers").getJSONObject("next")
                assertEquals(if(custom) "[]" else if(multiple) "[\"a\",\"b\"]" else "[\"b\"]", answer.getJSONArray("optionIds").toString())
                if (custom) assertEquals("Use another plan", answer.getString("customText"))
            } finally { executor.shutdownNow() }
        }
    }
    /** Opt-in: uses the Connector's real projection and desktop IPC response route. */
    private fun desktopRoundTrip(custom: Boolean = false, asynchronous: Boolean = false) {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("desktopQuestionFixture") == "true")
        val origin = "http://127.0.0.1:18867"
        fun fixture(path: String): JSONObject = java.net.URL(origin + path).openConnection().apply {
            connectTimeout = 15000; readTimeout = 15000
        }.getInputStream().use { JSONObject(it.bufferedReader().readText()) }
        fixture("/fixture/reset?mode=" + if (asynchronous) "async" else "native")
        val api = SessionsApi()
        val notices = mergeRuntimeNotices(emptyList(), api.getSessionRuntimeNotices(origin, "fixture", "fixture").notices, true)
        val notice = notices.single { it.interactionType == "input_request" }
        val controller = SessionDetailController(api, object: AuthSessionReader {
            override fun readServerUrl() = origin
            override fun readAccessToken() = "fixture"
        })
        val submitted = CompletableFuture<Unit>()
        content(listOf(notice)) { n,a,i ->
            controller.respondNotice(n.sessionId, n.noticeId, a.actionId, i).fold(
                { submitted.complete(Unit) }, { submitted.completeExceptionally(it) })
        }
        compose.onNodeWithText("Choose a language").assertIsDisplayed()
        if (custom) compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Go from phone")
        else compose.onNodeWithText("Rust").performScrollTo().performClick()
        capture(if (asynchronous) "desktop-async" else if (custom) "desktop-custom" else "desktop-choice")
        compose.onNodeWithText(text(R.string.session_input_request_submit)).assertIsDisplayed().performClick()
        submitted.get(20, TimeUnit.SECONDS)
        val delivered = fixture("/fixture/delivery").getJSONArray("messages")
        assertEquals(1, delivered.length())
        val message = delivered.getJSONObject(0)
        assertEquals("owner", message.getString("targetClientId"))
        val params = message.getJSONObject("params")
        assertEquals("thread_existing", params.getString("conversationId"))
        if (asynchronous) {
            assertEquals("thread-follower-steer-turn", message.getString("method"))
            val reply = params.getJSONArray("input").getJSONObject(0).getString("text")
            assertTrue(reply.startsWith("<send_user_message_question_reply>"))
            assertEquals("Go from phone", org.json.JSONArray(reply.substringAfter("<send_user_message_question_reply>").substringBefore("</send_user_message_question_reply>").trim()).getJSONObject(0).getString("answer"))
        } else {
            assertEquals("thread-follower-submit-user-input", message.getString("method"))
            assertEquals(42, params.getInt("requestId"))
            val answers = params.getJSONObject("response").getJSONObject("answers").getJSONObject("language").getJSONArray("answers")
            assertEquals(if (custom) "Go from phone" else "Rust", answers.getString(0))
        }
        val refreshed = api.getSessionRuntimeNotices(origin, "fixture", "fixture").notices.single { it.noticeId == notice.noticeId }
        assertEquals("resolved", refreshed.status)
        assertFalse(refreshed.responseRequired)
    }
    @Test fun desktopChoiceReachesOriginalOwner() = desktopRoundTrip()
    @Test fun desktopCustomTextReachesOriginalOwner() = desktopRoundTrip(custom = true)
    @Test fun desktopAsyncAnswerReachesOriginalOwner() = desktopRoundTrip(custom = true, asynchronous = true)
    @Test fun singleChoiceReplacesPreviousChoiceAndPosts() = submitToLocalServer()
    @Test fun multipleChoicesPostBothIds() = submitToLocalServer(multiple = true)
    @Test fun customAnswerPostsText() = submitToLocalServer(custom = true)
    @Test fun longQuestionWithLargeFontKeepsSubmitReachable() = submitToLocalServer(large = true)
    @Test fun cannotRespondWithoutTakeoverExplainsWhy() {
        val reason = text(R.string.session_response_takeover_required)
        var calls = 0
        content(listOf(question()), allowed = false, reason = reason) { _,_,_ -> calls++ }
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_input_request_submit)).performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, calls) }
        capture("takeover-required")
    }
    @Test fun offlineDeviceExplainsWhyAnswerCannotBeSent() {
        val reason = text(R.string.session_response_device_offline)
        var calls = 0
        content(listOf(question()), allowed = false, reason = reason) { _,_,_ -> calls++ }
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_input_request_submit)).performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, calls) }
        capture("device-offline")
    }
    @Test fun unsupportedQuestionDoesNotSendMalformedReply() {
        var calls = 0
        content(listOf(question(version = 99))) { _,_,_ -> calls++ }
        compose.onNodeWithText(text(R.string.session_question_unsupported)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.session_input_request_submit)).performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, calls) }
        capture("unsupported-question")
    }
}
