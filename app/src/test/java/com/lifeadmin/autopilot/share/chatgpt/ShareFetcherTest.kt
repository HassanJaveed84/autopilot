package com.lifeadmin.autopilot.share.chatgpt

import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.ID
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.URL
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ShareFetcherTest {
    private val link = ShareLink.parse(URL)!!
    private val html = "text/html; charset=utf-8"

    /** Scripted transport that records every URL it was asked for. */
    private class Scripted(vararg responses: (String) -> RawResponse) : ShareTransport {
        val requested = ArrayList<String>()
        private val queue = ArrayDeque(responses.toList())
        override fun get(url: String, maxBytes: Int): RawResponse { requested += url; return queue.removeFirst()(url) }
    }

    private fun ok(body: String = "<html></html>", type: String? = html, truncated: Boolean = false): (String) -> RawResponse =
        { RawResponse(200, type, null, body.toByteArray(), truncated) }
    private fun redirect(to: String, status: Int = 302): (String) -> RawResponse = { RawResponse(status, html, to, ByteArray(0), false) }
    private fun status(code: Int): (String) -> RawResponse = { RawResponse(code, html, null, ByteArray(0), false) }

    @Test fun fetchesOnlyTheCanonicalUrl() = runBlocking {
        val t = Scripted(ok("<html>page</html>"))
        assertEquals(FetchResult.Page("<html>page</html>"), ShareFetcher(t).fetch(ShareLink.parse("$URL?ogimg=plain")!!))
        assertEquals(listOf(URL), t.requested)
    }

    @Test fun followsARedirectToTheSameShare() = runBlocking {
        val t = Scripted(redirect("/share/$ID/"), ok())
        assertTrue(ShareFetcher(t).fetch(link) is FetchResult.Page)
        assertEquals(listOf(URL, "$URL/"), t.requested)
    }

    @Test fun neverFollowsARedirectAwayFromTheShare() = runBlocking {
        listOf(
            "https://evil.example/share/$ID" to "evil.example",
            "http://chatgpt.com/share/$ID" to "chatgpt.com",
            "https://chatgpt.com/auth/login" to "chatgpt.com",
            "https://chatgpt.com/share/11111111-2222-4333-8444-555555555555" to "chatgpt.com",
            "//evil.example/share/$ID" to "evil.example",
        ).forEach { (to, host) ->
            val t = Scripted(redirect(to), ok())
            assertEquals(to, FetchResult.RedirectBlocked(host), ShareFetcher(t).fetch(link))
            assertEquals("nothing requested after a blocked redirect", listOf(URL), t.requested)
        }
        val missing = Scripted({ RawResponse(301, html, null, ByteArray(0), false) })
        assertEquals(FetchResult.RedirectBlocked(null), ShareFetcher(missing).fetch(link))
    }

    @Test fun stopsARedirectLoop() = runBlocking {
        val t = Scripted(redirect(URL), redirect(URL), redirect(URL), redirect(URL), ok())
        assertEquals(FetchResult.TooManyRedirects, ShareFetcher(t, FetchLimits(maxRedirects = 3)).fetch(link))
        assertEquals(4, t.requested.size)
    }

    @Test fun mapsStatusAndBodyProblems() = runBlocking {
        assertEquals(FetchResult.HttpError(404), ShareFetcher(Scripted(status(404))).fetch(link))
        assertEquals(FetchResult.Refused(403), ShareFetcher(Scripted(status(403))).fetch(link))
        assertEquals(FetchResult.Refused(429), ShareFetcher(Scripted(status(429))).fetch(link))
        assertEquals(FetchResult.NotHtml("application/json"), ShareFetcher(Scripted(ok("{}", "application/json"))).fetch(link))
        assertEquals(FetchResult.NotHtml(null), ShareFetcher(Scripted(ok("x", null))).fetch(link))
        assertEquals(FetchResult.TooLarge, ShareFetcher(Scripted(ok(truncated = true))).fetch(link))
        assertEquals(FetchResult.Timeout, ShareFetcher(Scripted({ throw SocketTimeoutException() })).fetch(link))
        assertEquals(FetchResult.Network("IOException"), ShareFetcher(Scripted({ throw IOException("offline") })).fetch(link))
    }

    @Test fun enforcesTheTotalDeadline() = runBlocking {
        val slow = ShareTransport { _, _ -> Thread.sleep(400); RawResponse(200, html, null, ByteArray(0), false) }
        assertEquals(FetchResult.Timeout, ShareFetcher(slow, FetchLimits(totalTimeoutMs = 100)).fetch(link))
    }

    // ---- the real HttpURLConnection transport, against a local server (no chatgpt.com involved)

    @Test fun transportDoesNotFollowRedirectsOrSendCookies() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
            server.start()
            val r = UrlConnectionTransport().get(server.url("/share/$ID").toString(), 1024)
            assertEquals(302, r.status)
            assertEquals("https://evil.example/", r.location)
            assertEquals(1, server.requestCount)
            val req = server.takeRequest()
            assertNull(req.getHeader("Cookie"))
            assertNull(req.getHeader("Authorization"))
            assertEquals("text/html", req.getHeader("Accept"))
        }
    }

    @Test fun transportCapsTheBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", html).setBody("x".repeat(100_000)))
            server.start()
            val r = UrlConnectionTransport().get(server.url("/").toString(), 10_000)
            assertTrue(r.truncated)
            assertEquals(10_000, r.body.size)
            assertEquals(html, r.contentType)
        }
    }

    @Test fun transportTimesOutOnASilentServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val e = runCatching { UrlConnectionTransport(connectTimeoutMs = 1_000, readTimeoutMs = 300).get(server.url("/").toString(), 1024) }.exceptionOrNull()
            assertTrue("$e", e is SocketTimeoutException)
        }
    }

    @Test fun transportStopsADripFeed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", html).setBody("y".repeat(64 * 1024)).throttleBody(1024, 50, TimeUnit.MILLISECONDS))
            server.start()
            val e = runCatching { UrlConnectionTransport(readTimeoutMs = 2_000, maxDurationMs = 300).get(server.url("/").toString(), 1024 * 1024) }.exceptionOrNull()
            assertTrue("$e", e is SocketTimeoutException)
        }
    }
}
