package com.agentsanywhere.app.feature.sessiondetail

enum class RuntimeFailureKind { Quota, RateLimit, Execution }

/** A blocking error is still visible, but cannot become a question without actions. */
val RuntimeNotice.requiresUserResponse: Boolean
    get() = respondable

val RuntimeNotice.failureKind: RuntimeFailureKind?
    get() = if (interactionType == "execution_error" || (!requiresUserResponse && severity == "error")) {
        runtimeFailureKind(context["error"], message) ?: RuntimeFailureKind.Execution
    } else null

fun runtimeFailureKind(error: Any?, message: String? = null): RuntimeFailureKind? {
    val text = (errorText(error) + " " + message.orEmpty()).lowercase()
    if (listOf("usage limit", "usage_limit", "usagelimit", "insufficient_quota", "quota_exceeded", "quota exhausted", "quota exceeded", "insufficient credits", "out of credits", "credits exhausted").any(text::contains)) {
        return RuntimeFailureKind.Quota
    }
    if (listOf("rate_limit", "ratelimit", "rate limit", "too many requests").any(text::contains)) {
        return RuntimeFailureKind.RateLimit
    }
    return null
}

private fun errorText(value: Any?, depth: Int = 0): String {
    if (depth > 4) return ""
    return when (value) {
        is String -> value
        is Map<*, *> -> listOf("code", "type", "message", "detail", "additionalDetails", "codexErrorInfo", "error", "raw")
            .joinToString(" ") { errorText(value[it], depth + 1) }
        else -> ""
    }
}

/** Real questions remain accessible even when an older failure is still visible. */
fun List<RuntimeNotice>.prioritizeResponses(): List<RuntimeNotice> = sortedWith(
    compareBy<RuntimeNotice> { if (it.requiresUserResponse) 0 else if (it.status in setOf("responding", "response_accepted", "resolving")) 1 else 2 }
        .thenBy { it.updatedSeq }.thenBy { it.noticeId },
)
