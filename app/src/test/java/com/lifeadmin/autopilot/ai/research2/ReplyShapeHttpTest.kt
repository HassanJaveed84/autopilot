package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchCalls
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.testing.FAKE_KEY
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/**
 * Real Messages API reply shapes through the SDK and the call adapter, as they can arrive from a
 * Claude 5.5 model with adaptive thinking on by default: thinking blocks with no text cut off by
 * `max_tokens`, a tool-only `pause_turn`, an empty `end_turn`. The adapter reports the shape of each
 * reply (kinds and counts only) so the orchestrator can say what came back instead of "malformed".
 */
class ReplyShapeHttpTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun calls(): AnthropicResearchCalls = AnthropicResearchCalls(AnthropicResearchProvider(baseUrl = server.url("/").toString().trimEnd('/'), timeout = Duration.ofSeconds(5)), timeout = Duration.ofSeconds(5))
    private fun enqueue(body: String) { server.enqueue(MockResponse().setResponseCode(200).setHeader("content-type", "application/json").setBody(body)) }
    private fun message(blocks: List<String>, stop: String, outputTokens: Long, searches: Int) =
        """{"id":"msg_shape","type":"message","role":"assistant","model":"claude-haiku-5-5","content":[${blocks.joinToString(",")}],"stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":9800,"output_tokens":$outputTokens,"server_tool_use":{"web_search_requests":$searches}}}"""
    private val thinking = """{"type":"thinking","thinking":"","signature":"sig"}"""
    private val redacted = """{"type":"redacted_thinking","data":"opaque"}"""
    private fun search(id: String) = listOf(
        """{"type":"server_tool_use","id":"$id","name":"web_search","input":{"query":"q $id"}}""",
        """{"type":"web_search_tool_result","tool_use_id":"$id","content":[{"type":"web_search_result","url":"https://a.example/$id","title":"T","encrypted_content":"e","page_age":null}]}""",
    )
    private fun text(t: String, cited: Boolean = false) = if (!cited) """{"type":"text","text":"$t"}""" else
        """{"type":"text","text":"$t","citations":[{"type":"web_search_result_location","url":"https://a.example/s1","title":"T","encrypted_index":"i","cited_text":"c"}]}"""

    private val call = ModelCall("claude-haiku-5-5", "SYS", "USER", 1_500, 3)

    @Test fun thinkingAndOneSearchCutOffByMaxTokensBeforeAnyTextIsReportedAsSuch() {
        // The real-device continuation: thinking (empty under the default display) + one search, then max_tokens.
        enqueue(message(listOf(thinking) + search("s1") + listOf(thinking, redacted), "max_tokens", 1_500, 1))
        val r = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        assertEquals(CallStop.MAX_TOKENS, r.stop)
        assertTrue("no text block: nothing to parse", r.segments.isEmpty())
        assertEquals(1, r.searches.size)
        assertEquals(1, r.usage.webSearches)
        assertEquals(1_500L, r.usage.outputTokens)
        val shape = r.shape!!
        assertEquals(mapOf("thinking" to 2, "server_tool_use" to 1, "web_search_tool_result" to 1, "redacted_thinking" to 1), shape.blocks)
        assertEquals(0, shape.textChars); assertEquals(0, shape.citedBlocks)
    }

    @Test fun toolOnlyPauseTurnHandsBackThePriorAndItsShape() {
        enqueue(message(search("s1") + search("s2") + search("s3"), "pause_turn", 240, 3))
        val r = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        assertEquals(CallStop.PAUSE_TURN, r.stop)
        assertNotNull("the paused turn is kept so it can be resumed when the budget allows", r.paused)
        assertTrue(r.segments.isEmpty())
        assertEquals(3, r.searches.size); assertEquals(3, r.usage.webSearches)
        assertEquals(mapOf("server_tool_use" to 3, "web_search_tool_result" to 3), r.shape!!.blocks)
        assertEquals(0, r.shape!!.textChars)
    }

    @Test fun emptyEndTurnAndCitedTextAreShapedTruthfully() {
        enqueue(message(emptyList(), "end_turn", 0, 0))
        val empty = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        assertEquals(CallStop.END, empty.stop)
        assertEquals(emptyMap<String, Int>(), empty.shape!!.blocks)
        assertNull(empty.paused)
        enqueue(message(listOf(thinking) + search("s1") + listOf(text("## ANSWER\\n"), text("Fact.", cited = true)), "end_turn", 420, 1))
        val ok = runBlocking { calls().call(call, FAKE_KEY) } as CallResult.Done
        assertEquals(mapOf("thinking" to 1, "server_tool_use" to 1, "web_search_tool_result" to 1, "text" to 2), ok.shape!!.blocks)
        assertEquals("## ANSWER".length + "Fact.".length, ok.shape!!.textChars)
        assertEquals(1, ok.shape!!.citedBlocks)
        // The shape never carries content.
        assertTrue(ok.shape.toString(), !ok.shape.toString().contains("Fact") && !ok.shape.toString().contains("a.example"))
    }
}
