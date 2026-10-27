package com.lifeadmin.autopilot.share.applink

import java.net.URI

/*
 * ChatGPT → Android App Link spike (docs/CHATGPT_APP_LINK_SPIKE.md). One fixed route, one harmless
 * payload. The link is display-only: nothing here is ever interpreted as a command or turned into a
 * task change. Deliberately not a routing framework.
 */

/** Why a link was refused. Shown to the user by category only; the payload itself is never echoed. */
enum class TestLinkProblem { MALFORMED, NOT_HTTPS, WRONG_HOST, WRONG_PATH, NO_MESSAGE, INVALID_MESSAGE }

sealed interface TestLinkResult {
    data class Received(val message: String) : TestLinkResult
    data class Rejected(val problem: TestLinkProblem) : TestLinkResult

    /** A structured `ap=v1` fragment on the same route ([HandoffFragment]); display only. */
    data class HandoffReceived(val handoff: LinkHandoff) : TestLinkResult
    data class HandoffRejected(val issue: HandoffIssue) : TestLinkResult
}

/**
 * Validates `https://<host>/autopilot/test#message=<value>`.
 *
 * - Scheme exactly `https`, host exactly [host] (case-insensitive, no subdomains), no user info,
 *   default port, path exactly [PATH].
 * - The payload travels in the fragment, which browsers never send to the website. Its only form is
 *   `message=<1–64 URL-unreserved characters>`; percent-escapes, extra keys and separators are refused
 *   rather than decoded or guessed.
 * - A query string is ignored (chat apps may append tracking such as `utm_source`); it is never read.
 * - A fragment starting `ap=` is the structured handoff experiment instead, validated by [HandoffFragment].
 */
class TestLinkParser(private val host: String) {
    fun parse(raw: String?): TestLinkResult {
        val fragment = when (val e = AppLinkEnvelope.fragment(raw, host, PATH, MAX_URL)) {
            is AppLinkEnvelope.Result.Ok -> e.fragment
            is AppLinkEnvelope.Result.Refused -> return TestLinkResult.Rejected(e.problem)
        }
        if (fragment != null && fragment.startsWith(HandoffFragment.PREFIX)) {
            return when (val r = HandoffFragment.parse(fragment)) {
                is HandoffParseResult.Valid -> TestLinkResult.HandoffReceived(r.handoff)
                is HandoffParseResult.Invalid -> TestLinkResult.HandoffRejected(r.issue)
            }
        }
        if (fragment.isNullOrEmpty() || !fragment.startsWith(KEY)) return TestLinkResult.Rejected(TestLinkProblem.NO_MESSAGE)
        val message = fragment.removePrefix(KEY)
        if (!MESSAGE.matches(message)) return TestLinkResult.Rejected(TestLinkProblem.INVALID_MESSAGE)
        return TestLinkResult.Received(message)
    }

    companion object {
        const val PATH = "/autopilot/test"
        const val TEST_MESSAGE = "hello-from-chatgpt"
        private const val KEY = "message="
        private const val MAX_URL = 2_048
        private val MESSAGE = Regex("[A-Za-z0-9._~-]{1,64}")

        fun testUrl(host: String, message: String = TEST_MESSAGE) = "https://$host$PATH#$KEY$message"
    }
}

/**
 * The checks every App Link route shares: scheme exactly `https`, host exactly [host]
 * (case-insensitive, no subdomains), no user info, default port, path exactly as given, and a
 * length cap applied before parsing. A query string is ignored and never read. Returns the raw
 * (still percent-encoded) fragment.
 */
internal object AppLinkEnvelope {
    sealed interface Result {
        data class Ok(val fragment: String?) : Result
        data class Refused(val problem: TestLinkProblem) : Result
    }

    fun fragment(raw: String?, host: String, path: String, maxUrl: Int): Result {
        if (raw == null || raw.length > maxUrl) return Result.Refused(TestLinkProblem.MALFORMED)
        val uri = runCatching { URI(raw) }.getOrNull() ?: return Result.Refused(TestLinkProblem.MALFORMED)
        if (!uri.scheme.equals("https", ignoreCase = true)) return Result.Refused(TestLinkProblem.NOT_HTTPS)
        if (uri.rawUserInfo != null || uri.port.let { it != -1 && it != 443 } || !uri.host.equals(host, ignoreCase = true)) {
            return Result.Refused(TestLinkProblem.WRONG_HOST)
        }
        if (uri.rawPath != path) return Result.Refused(TestLinkProblem.WRONG_PATH)
        return Result.Ok(uri.rawFragment)
    }
}
