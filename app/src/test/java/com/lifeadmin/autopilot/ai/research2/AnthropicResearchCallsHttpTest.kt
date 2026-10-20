package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.testing.FAKE_KEY
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The M3A.2 call adapter against MockWebServer: request shape (cache control, tool, prior turn), usage incl. cache tokens and message id, stop reasons, errors. */
class AnthropicResearchCallsHttpTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun calls(): AnthropicResearchCalls = AnthropicResearchCalls(AnthropicResearchProvider(baseUrl = server.url("/").toString().trimEnd('/'), timeout = Duration.ofSeconds(5)), timeout = Duration.ofSeconds(5))

    private fun str(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
    private fun enqueueJson(code: Int, body: String) { server.enqueue(MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)) }
    private fun message(blocks: List<String>, stop: String = "end_turn", usage: String = """{"input_tokens":1200,"output_tokens":480,"cache_creation_input_tokens":900,"cache_read_input_tokens":300,"cache_creation":{"ephemeral_5m_input_tokens":900,"ephemeral_1h_input_tokens":0},"server_tool_use":{"web_search_requests":2}}""") =
        """{"id":"msg_abc","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[${blocks.joinToString(",")}],"stop_reason":"$stop","stop_sequence":null,"usage":$usage}"""
    private fun text(t: String, url: String? = null) = if (url == null) """{"type":"text","text":${str(t)}}""" else
        """{"type":"text","text":${str(t)},"citations":[{"type":"web_search_result_location","url":${str(url)},"title":"T","encrypted_index":"i","cited_text":"c"}]}"""
    private fun search(id: String, q: String, url: String) =
        """{"type":"server_tool_use","id":"$id","name":"web_search","input":{"query":${str(q)}}},{"type":"web_search_tool_result","tool_use_id":"$id","content":[{"type":"web_search_result","url":${str(url)},"title":"T","encrypted_content":"e","page_age":null}]}"""

    private val call = ModelCall("claude-sonnet-5-5", "SYSTEM PROMPT", "USER TEXT", 6_000, 7, cacheSystem = true)

    @Test fun requestCarriesCacheControlToolCapAndNoLocation() {
        enqueueJson(200, message(listOf(search("s1", "q1", "https://a.example/1"), text("## ANSWER\n"), text("fact", "https://a.example/1"))))
        val result = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", recorded.path)
        assertEquals(FAKE_KEY, recorded.getHeader("x-api-key"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(6_000L, body["max_tokens"]!!.jsonPrimitive.long)
        val system = body["system"]!!.jsonArray[0].jsonObject
        assertEquals("SYSTEM PROMPT", system["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("5m", system["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals(Research2Config.TOOL_TYPE, tool["type"]!!.jsonPrimitive.content)
        assertEquals(7L, tool["max_uses"]!!.jsonPrimitive.long)
        assertNull(tool["user_location"])
        assertEquals(1, body["messages"]!!.jsonArray.size)
        // Usage, including cache tokens and the server's search count; message id for de-duplication.
        assertEquals(1200L, result.usage.inputTokens)
        assertEquals(480L, result.usage.outputTokens)
        assertEquals(900L, result.usage.cacheWrite5mTokens)
        assertEquals(0L, result.usage.cacheWrite1hTokens)
        assertEquals(300L, result.usage.cacheReadTokens)
        assertEquals(2, result.usage.webSearches)
        assertEquals("msg_abc", result.providerMessageId)
        assertEquals(CallStop.END, result.stop)
        assertNull(result.paused)
        assertEquals(1, result.searches.size)
        assertEquals("q1", result.searches[0].query)
        assertEquals(1, result.segments.count { it.citations.isNotEmpty() })
    }

    @Test fun plainSystemWithoutCacheAndNoToolWhenZeroSearches() {
        enqueueJson(200, message(listOf(text("## ANSWER\nhello")), usage = """{"input_tokens":10,"output_tokens":5}"""))
        val result = runBlocking { calls().call(call.copy(cacheSystem = false, maxSearches = 0), FAKE_KEY) } as CallResult.Done
        val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals("SYSTEM PROMPT", body["system"]!!.jsonPrimitive.content)
        assertNull(body["tools"])
        assertEquals("no server_tool_use → searches counted from result blocks (none)", 0, result.usage.webSearches)
        assertEquals(0L, result.usage.cacheReadTokens)
    }

    @Test fun pauseTurnHandsBackThePriorMessageAndItIsResent() {
        enqueueJson(200, message(listOf(search("s1", "q1", "https://a.example/1"), text("partial")), stop = "pause_turn"))
        val first = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        assertEquals(CallStop.PAUSE_TURN, first.stop)
        assertTrue(first.paused != null)
        server.takeRequest(5, TimeUnit.SECONDS)
        enqueueJson(200, message(listOf(text("## ANSWER\ndone")), usage = """{"input_tokens":10,"output_tokens":5,"server_tool_use":{"web_search_requests":0}}"""))
        val second = runBlocking { calls().call(call.copy(maxSearches = 5, prior = first.paused), FAKE_KEY) } as CallResult.Done
        val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(5L, body["tools"]!!.jsonArray[0].jsonObject["max_uses"]!!.jsonPrimitive.long)
        assertEquals(CallStop.END, second.stop)
    }

    @Test fun stopReasonsAndErrorsAreMapped() {
        enqueueJson(200, message(listOf(text("cut")), stop = "max_tokens"))
        assertEquals(CallStop.MAX_TOKENS, (runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done).stop)
        enqueueJson(200, """{"id":"m","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":"refusal","stop_sequence":null,"stop_details":{"type":"refusal","category":"harmful"},"usage":{"input_tokens":1,"output_tokens":0}}""")
        val refused = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        assertEquals(CallStop.REFUSAL, refused.stop)
        assertEquals("harmful", refused.refusalCategory)
        enqueueJson(400, """{"type":"error","error":{"type":"invalid_request_error","message":"Web search is not enabled for this organization"}}""")
        assertEquals(ResearchError.WebSearchNotEnabled, (runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Failed).error)
        enqueueJson(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")
        assertTrue((runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Failed).error is ResearchError.RateLimited)
        enqueueJson(529, """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""")
        assertEquals(ResearchError.ServerError(529), (runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Failed).error)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val net = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Failed
        assertTrue(net.error.toString(), net.error == ResearchError.Network || net.error is ResearchError.Unexpected || net.error is ResearchError.ServerError)
        assertNull("no usage is ever invented for a failed call", net.usage)
    }
}
