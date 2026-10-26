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
 */
class TestLinkParser(private val host: String) {
    fun parse(raw: String?): TestLinkResult {
        if (raw == null || raw.length > MAX_URL) return TestLinkResult.Rejected(TestLinkProblem.MALFORMED)
        val uri = runCatching { URI(raw) }.getOrNull() ?: return TestLinkResult.Rejected(TestLinkProblem.MALFORMED)
        if (!uri.scheme.equals("https", ignoreCase = true)) return TestLinkResult.Rejected(TestLinkProblem.NOT_HTTPS)
        if (uri.rawUserInfo != null || uri.port.let { it != -1 && it != 443 } || !uri.host.equals(host, ignoreCase = true)) {
            return TestLinkResult.Rejected(TestLinkProblem.WRONG_HOST)
        }
        if (uri.rawPath != PATH) return TestLinkResult.Rejected(TestLinkProblem.WRONG_PATH)
        val fragment = uri.rawFragment
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
