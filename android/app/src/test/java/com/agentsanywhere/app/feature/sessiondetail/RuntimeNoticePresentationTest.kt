package com.agentsanywhere.app.feature.sessiondetail

import org.junit.Assert.*
import org.junit.Test

class RuntimeNoticePresentationTest {
    private fun notice(id: String = "error") = RuntimeNotice(
        id, "interaction", "session", "Codex turn failed", "You've hit your usage limit. Try again later.",
        "error", "open", "execution_error", RuntimeNoticeBlocking("session", "session"), false,
        1, 1, emptyMap(), emptyList(), emptyMap(), emptyMap(), null, null, null, null,
    )
    private val action = RuntimeNoticeAction("submit", "Submit", "primary", RuntimeNoticeActionInput(false, null, null), emptyMap())

    @Test fun quotaFailureIsVisibleButDoesNotAskForResponse() {
        val error = notice()
        assertTrue(error.openInteraction)
        assertTrue(error.blocksSession("session"))
        assertFalse(error.requiresUserResponse)
        assertEquals(RuntimeFailureKind.Quota, error.failureKind)
    }
    @Test fun structuredUsageErrorsAndRateLimitsAreDistinct() {
        assertEquals(RuntimeFailureKind.Quota, runtimeFailureKind(mapOf("codexErrorInfo" to mapOf("type" to "UsageLimitExceeded"))))
        assertEquals(RuntimeFailureKind.Quota, runtimeFailureKind(mapOf("code" to "insufficient_quota")))
        assertEquals(RuntimeFailureKind.RateLimit, runtimeFailureKind(mapOf("code" to "rate_limit_exceeded")))
        assertNull(runtimeFailureKind(mapOf("code" to "429", "message" to "HTTP failure")))
        assertEquals(RuntimeFailureKind.Execution, notice().copy(message = "Connection failed").failureKind)
    }
    @Test fun realQuestionIsAheadOfOldErrorAndAcceptedAnswers() {
        val question = notice("question").copy(interactionType = "input_request", responseRequired = true, actions = listOf(action), severity = "info")
        val accepted = question.copy(noticeId = "accepted", status = "response_accepted")
        assertTrue(question.requiresUserResponse)
        assertFalse(accepted.requiresUserResponse)
        assertNull(question.failureKind)
        assertEquals(listOf("question", "accepted", "error"), listOf(notice(), accepted, question).prioritizeResponses().map { it.noticeId })
        assertFalse(question.copy(status = "resolved").requiresUserResponse)
        assertFalse(question.copy(actions = emptyList()).requiresUserResponse)
    }
}
