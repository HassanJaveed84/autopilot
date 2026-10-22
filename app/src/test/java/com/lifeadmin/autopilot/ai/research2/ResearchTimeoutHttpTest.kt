package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.ai.research.DetailField
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research.ResearchFocus
import com.lifeadmin.autopilot.ai.research.ResearchRequest
import com.lifeadmin.autopilot.ai.research.ResearchResponse
import com.lifeadmin.autopilot.testing.FAKE_KEY
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * P0 real-device failure (Galaxy S24 Ultra, first paid Deep run): "CLIENT TIMEOUT (60 S)" after ~60 s
 * although M3A.2 intended 180 s. The M3A.2 call adapter reused the legacy M3A HTTP client, whose
 * SDK/OkHttp timeout was 60 s, so the surrounding coroutine timeout never mattered.
 *
 * These tests drive the real SDK HTTP client against MockWebServer with short injected durations.
 */
class ResearchTimeoutHttpTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun base(legacyTimeout: Duration) = AnthropicResearchProvider(baseUrl = server.url("/").toString().trimEnd('/'), timeout = legacyTimeout)

    private val okBody = """{"id":"msg_slow","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"## ANSWER\nslow but fine"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5,"server_tool_use":{"web_search_requests":0}}}"""

    private fun enqueueDelayed(delayMs: Long) {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(okBody).setHeadersDelay(delayMs, TimeUnit.MILLISECONDS))
    }

    private val legacyRequest = ResearchRequest("t1", "q", setOf(ResearchFocus.WHAT_TO_DO), emptyMap<DetailField, String>(), null, null, null, null)

    @Test fun deepResearchCallSurvivesAReplySlowerThanTheLegacyTimeoutButWithinItsOwn() {
        // Legacy client: 1 s. M3A.2 call: 5 s. Reply after 2 s. Before the fix this failed with Timeout at ~1 s.
        val calls = AnthropicResearchCalls(base(Duration.ofSeconds(1)), timeout = Duration.ofSeconds(5))
        enqueueDelayed(2_000)
        val started = System.nanoTime()
        val result = runBlocking { calls.call(ModelCall("claude-opus-5-5", "SYS", "USER", 1_000, 3, timeout = Duration.ofSeconds(5)), FAKE_KEY) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("expected a successful reply after ~2 s, got $result after $elapsedMs ms", result is CallResult.Done)
        assertEquals("msg_slow", (result as CallResult.Done).providerMessageId)
        assertEquals(1, server.requestCount)
    }

    @Test fun deepResearchCallTimesOutAtItsOwnConfiguredLimitAndSaysSo() {
        val calls = AnthropicResearchCalls(base(Duration.ofSeconds(30)), timeout = Duration.ofSeconds(1))
        enqueueDelayed(3_000)
        val started = System.nanoTime()
        val result = runBlocking { calls.call(ModelCall("claude-opus-5-5", "SYS", "USER", 1_000, 3, timeout = Duration.ofSeconds(1)), FAKE_KEY) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.toString(), result is CallResult.Failed)
        val error = (result as CallResult.Failed).error
        assertTrue("$error", error is ResearchError.Timeout)
        assertEquals("the message names the timeout that was actually configured for this call", 1, (error as ResearchError.Timeout).seconds)
        assertTrue(error.technical.contains("1 S"))
        assertTrue("timed out near its own limit, not the 30 s client default: $elapsedMs ms", elapsedMs < 2_800)
        assertEquals("never retried on its own", 1, server.requestCount)
    }

    @Test fun legacyResearchKeepsItsOwnShorterTimeout() {
        val legacy = base(Duration.ofMillis(800))
        // The server never answers the legacy request: the legacy flow gives up at its own 800 ms.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val f = runBlocking { legacy.research(legacyRequest, FAKE_KEY, AiConfig.DEFAULT_MODEL) {} }
        assertTrue(f.toString(), f is ResearchResponse.Failure && (f as ResearchResponse.Failure).error is ResearchError.Timeout)
        server.takeRequest(2, TimeUnit.SECONDS)
        // The same base provider still serves a longer M3A.2 call afterwards: client reuse must not pin the short
        // timeout, and the socket the timed-out request left behind is never reused (the client is rebuilt).
        val calls = AnthropicResearchCalls(legacy, timeout = Duration.ofSeconds(5))
        enqueueDelayed(1_500)
        val ok = runBlocking { calls.call(ModelCall("claude-opus-5-5", "SYS", "USER", 1_000, 0, timeout = Duration.ofSeconds(5)), FAKE_KEY) }
        assertTrue(ok.toString(), ok is CallResult.Done)
        assertEquals(2, server.requestCount)
    }

    @Test fun apiKeyChangeAndResetRebuildTheClientWithoutLosingTheCallTimeout() {
        val legacy = base(Duration.ofMillis(800))
        val calls = AnthropicResearchCalls(legacy, timeout = Duration.ofSeconds(5))
        enqueueDelayed(1_200)
        assertTrue(runBlocking { calls.call(ModelCall("m", "S", "U", 100, 0, timeout = Duration.ofSeconds(5)), "sk-ant-first") } is CallResult.Done)
        enqueueDelayed(1_200)
        assertTrue(runBlocking { calls.call(ModelCall("m", "S", "U", 100, 0, timeout = Duration.ofSeconds(5)), "sk-ant-second") } is CallResult.Done)
        legacy.reset()
        enqueueDelayed(1_200)
        assertTrue(runBlocking { calls.call(ModelCall("m", "S", "U", 100, 0, timeout = Duration.ofSeconds(5)), "sk-ant-second") } is CallResult.Done)
        assertEquals(listOf("sk-ant-first", "sk-ant-second", "sk-ant-second"), (1..3).map { server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("x-api-key") })
    }
}
