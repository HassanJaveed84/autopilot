package com.lifeadmin.autopilot.ai.research

import com.lifeadmin.autopilot.ai.AiConfig
import com.lifeadmin.autopilot.ai.anthropic.AnthropicResearchProvider
import com.lifeadmin.autopilot.testing.FAKE_KEY
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Exercises the real research adapter (official SDK + OkHttp, server-side web_search tool) against
 * a local MockWebServer: request shape and caps, citation/search-result parsing, pause_turn
 * continuation within the search budget, and every error class. No real key, no live API, no
 * paid search is involved; live verification is documented as pending in docs/M3_RESEARCH_REPORT.md.
 */
class AnthropicResearchProviderHttpTest {
    private lateinit var server: MockWebServer

    private val request = ResearchRequest(
        taskId = "t1",
        question = "When do advance tickets go on sale and what do they cost?",
        focus = setOf(ResearchFocus.COSTS, ResearchFocus.DEADLINES),
        details = mapOf(DetailField.FROM to "Leeds", DetailField.TO to "London", DetailField.OPERATOR to "Northline Rail"),
        title = "Book train ride",
        notes = "Window seat if possible",
        steps = null,
        localeLine = "Tue 20 Oct 2026 · English (United Kingdom)",
    )

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun provider(timeout: Duration = Duration.ofSeconds(10)) =
        AnthropicResearchProvider(baseUrl = server.url("/").toString().trimEnd('/'), timeout = timeout)

    private fun research(p: AnthropicResearchProvider = provider(), progress: MutableList<ResearchProgress> = mutableListOf()) =
        runBlocking { p.research(request, FAKE_KEY, AiConfig.DEFAULT_MODEL) { progress += it } }

    private fun enqueueJson(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val r = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)
        headers.forEach { (k, v) -> r.setHeader(k, v) }
        server.enqueue(r)
    }

    private fun error(type: String, message: String = "test") = """{"type":"error","error":{"type":"$type","message":${Json.encodeToString(kotlinx.serialization.serializer<String>(), message)}}}"""

    private fun str(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    private fun serverToolUse(id: String, query: String) =
        """{"type":"server_tool_use","id":"$id","name":"web_search","input":{"query":${str(query)}}}"""

    private fun searchResult(toolUseId: String, vararg results: Triple<String, String, String?>) =
        """{"type":"web_search_tool_result","tool_use_id":"$toolUseId","content":[${results.joinToString(",") { (url, title, age) ->
            """{"type":"web_search_result","url":${str(url)},"title":${str(title)},"encrypted_content":"enc","page_age":${age?.let(::str) ?: "null"}}"""
        }}]}"""

    private fun searchError(toolUseId: String, code: String) =
        """{"type":"web_search_tool_result","tool_use_id":"$toolUseId","content":{"type":"web_search_tool_result_error","error_code":"$code"}}"""

    private fun text(text: String, vararg citations: Triple<String, String, String>) =
        """{"type":"text","text":${str(text)}${if (citations.isEmpty()) "" else ""","citations":[${citations.joinToString(",") { (url, title, cited) ->
            """{"type":"web_search_result_location","url":${str(url)},"title":${str(title)},"encrypted_index":"idx","cited_text":${str(cited)}}"""
        }}]"""}}"""

    private fun message(blocks: List<String>, stop: String = "end_turn", searches: Int = 1, stopDetails: String = "null") = """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[${blocks.joinToString(",")}],
         "stop_reason":"$stop","stop_sequence":null,"stop_details":$stopDetails,
         "usage":{"input_tokens":1200,"output_tokens":480,"server_tool_use":{"web_search_requests":$searches}}}
    """.trimIndent()

    private val railUrl = "https://www.northlinerail.example/tickets"
    private val govUrl = "https://www.gov.uk/rail-fares"

    private fun fullAnswer(): List<String> = listOf(
        serverToolUse("srvtoolu_1", "Northline Rail advance tickets release"),
        searchResult("srvtoolu_1", Triple(railUrl, "Tickets | Northline Rail", "September 2026"), Triple("https://travelblog.example/tips", "Cheap train tips", null)),
        text("## QUICK ANSWER\n"),
        text("Advance tickets go on sale 12 weeks before travel.", Triple(railUrl, "Tickets | Northline Rail", "Advance tickets are released 12 weeks ahead.")),
        text(" Prices vary by route; see https://notacitation.example for more.\n\n## NEXT MOVES\n- STEP: Check the 12-week release date — "),
        text("Northline releases advance fares 12 weeks out.", Triple(railUrl, "Tickets | Northline Rail", "released 12 weeks ahead")),
        text("\n\n## FINDINGS\n- "),
        text("Fares are regulated for some routes.", Triple(govUrl, "Rail fares", "Regulated fares")),
        text("\n\n## UNCONFIRMED\n- none"),
    )

    @Test fun sendsWebSearchToolWithCapsAndNoLocation() {
        enqueueJson(200, message(fullAnswer()))
        val result = research()
        assertTrue(result.toString(), result is ResearchResponse.Success)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", recorded.path)
        assertEquals(FAKE_KEY, recorded.getHeader("x-api-key"))
        assertNull("research never asks for server-side fallbacks", recorded.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(AiConfig.DEFAULT_MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals(ResearchConfig.MAX_OUTPUT_TOKENS, body["max_tokens"]!!.jsonPrimitive.long)
        val tools = body["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val tool = tools[0].jsonObject
        assertEquals(ResearchConfig.TOOL_TYPE, tool["type"]!!.jsonPrimitive.content)
        assertEquals(ResearchConfig.TOOL_NAME, tool["name"]!!.jsonPrimitive.content)
        assertEquals(ResearchConfig.MAX_SEARCHES_PER_REQUEST.toLong(), tool["max_uses"]!!.jsonPrimitive.long)
        assertNull("no user_location is ever sent", tool["user_location"])
        assertNull(body["fallbacks"])
        assertNull("no structured output; the brief is plain text", body["output_config"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val user = messages[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(user.contains("Research question: When do advance tickets"))
        assertTrue(user.contains("Operator or service: Northline Rail"))
        assertTrue(user.contains("Task notes: Window seat if possible"))
        assertFalse("steps were not shared", user.contains("Current steps"))
        assertTrue(user.contains("Searches allowed: 3."))
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("## QUICK ANSWER"))
    }

    @Test fun parsesCitationsSearchesAndUsage() {
        enqueueJson(200, message(fullAnswer()))
        val progress = mutableListOf<ResearchProgress>()
        val result = research(progress = progress) as ResearchResponse.Success
        assertEquals(listOf(ResearchProgress.Sent, ResearchProgress.Writing), progress)
        assertFalse(result.truncated)
        assertEquals(1, result.searches.size)
        assertEquals("Northline Rail advance tickets release", result.searches[0].query)
        assertEquals(listOf(railUrl, "https://travelblog.example/tips"), result.searches[0].results.map { it.url })
        assertEquals("September 2026", result.searches[0].results[0].pageAge)
        assertNull(result.searches[0].errorCode)
        val cited = result.segments.filter { it.citations.isNotEmpty() }
        assertEquals(3, cited.size)
        assertEquals(railUrl, cited[0].citations[0].url)
        assertEquals("Advance tickets are released 12 weeks ahead.", cited[0].citations[0].citedText)
        assertEquals(ResearchUsage(1200, 480, 1, AiConfig.DEFAULT_MODEL, result.usage.elapsedMs, false), result.usage)

        // And the parser turns it into a verified brief with the prose URL removed.
        val brief = BriefParser.parse(result.segments, result.searches, result.usage, result.truncated, request.sharedSummary()) { "m" }
        assertTrue(brief.verified)
        assertEquals(listOf(railUrl, govUrl, "https://travelblog.example/tips"), brief.sources.map { it.url })
        assertTrue(brief.quickAnswer.none { it.text.contains("notacitation") })
        assertEquals(SourceTier.WEB, brief.sources[0].tier) // operator-named host, not a government domain
        assertEquals(SourceTier.OFFICIAL, brief.sources[1].tier) // gov.uk
    }

    @Test fun pauseTurnIsContinuedOnceWithinTheSearchBudget() {
        enqueueJson(
            200,
            message(
                listOf(
                    serverToolUse("srvtoolu_1", "first query"),
                    searchResult("srvtoolu_1", Triple(railUrl, "Tickets", null)),
                    serverToolUse("srvtoolu_2", "second query"),
                    searchResult("srvtoolu_2", Triple(govUrl, "Fares", null)),
                ),
                stop = "pause_turn", searches = 2,
            ),
        )
        enqueueJson(200, message(listOf(serverToolUse("srvtoolu_3", "third query"), searchResult("srvtoolu_3", Triple(railUrl, "Tickets", null)), text("## QUICK ANSWER\nDone.", Triple(railUrl, "Tickets", "x"))), searches = 1))
        val progress = mutableListOf<ResearchProgress>()
        val result = research(progress = progress) as ResearchResponse.Success
        assertEquals(listOf(ResearchProgress.Sent, ResearchProgress.Searching(2), ResearchProgress.Continuing, ResearchProgress.Writing), progress)
        assertEquals(3, result.usage.webSearches)
        assertTrue(result.usage.continuedOnce)
        assertEquals(2400, result.usage.inputTokens)
        assertEquals(listOf("first query", "second query", "third query"), result.searches.map { it.query })
        assertEquals(2, server.requestCount)
        server.takeRequest(5, TimeUnit.SECONDS)
        val second = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        // The continuation carries the paused assistant turn and only the remaining budget.
        assertEquals(1L, second["tools"]!!.jsonArray[0].jsonObject["max_uses"]!!.jsonPrimitive.long)
        val msgs = second["messages"]!!.jsonArray
        assertEquals(2, msgs.size)
        assertEquals("assistant", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(msgs[1].jsonObject["content"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "server_tool_use" })
    }

    @Test fun pauseTurnWithBudgetSpentIsNotContinued() {
        val blocks = (1..3).flatMap { listOf(serverToolUse("s$it", "q$it"), searchResult("s$it", Triple(railUrl, "Tickets", null))) }
        enqueueJson(200, message(blocks, stop = "pause_turn", searches = 3))
        val result = research()
        assertEquals(1, server.requestCount)
        assertTrue(result is ResearchResponse.Failure)
        assertTrue((result as ResearchResponse.Failure).error is ResearchError.Malformed)
        assertEquals(3, result.searchesRan)
    }

    @Test fun secondPauseTurnIsNotContinuedAgainButKeepsAnyText() {
        enqueueJson(200, message(listOf(serverToolUse("s1", "q1"), searchResult("s1", Triple(railUrl, "Tickets", null))), stop = "pause_turn", searches = 1))
        enqueueJson(200, message(listOf(serverToolUse("s2", "q2"), searchResult("s2", Triple(govUrl, "Fares", null)), text("## QUICK ANSWER\nPartial.", Triple(govUrl, "Fares", "x"))), stop = "pause_turn", searches = 1))
        val result = research() as ResearchResponse.Success
        assertEquals(2, server.requestCount)
        assertTrue("still paused counts as incomplete", result.truncated)
        assertEquals(2, result.usage.webSearches)
    }

    @Test fun maxTokensIsASuccessMarkedTruncated() {
        enqueueJson(200, message(listOf(text("## QUICK ANSWER\nHalf an answer", Triple(railUrl, "Tickets", "x"))), stop = "max_tokens"))
        val result = research() as ResearchResponse.Success
        assertTrue(result.truncated)
    }

    @Test fun refusalNeverReachesParsing() {
        enqueueJson(200, message(listOf(text("## QUICK ANSWER\nignored")), stop = "refusal", stopDetails = """{"type":"refusal","category":"other"}"""))
        val f = research() as ResearchResponse.Failure
        assertEquals(ResearchError.Refused("other"), f.error)
    }

    @Test fun searchToolErrorWithoutAnswerIsReported() {
        enqueueJson(200, message(listOf(serverToolUse("s1", "q1"), searchError("s1", "max_uses_exceeded")), searches = 1))
        val f = research() as ResearchResponse.Failure
        assertEquals(ResearchError.SearchTool("max_uses_exceeded"), f.error)
        assertEquals(1, f.searchesRan)
    }

    @Test fun emptyAnswerIsMalformed() {
        enqueueJson(200, message(emptyList(), searches = 0))
        val f = research() as ResearchResponse.Failure
        assertTrue(f.error is ResearchError.Malformed)
    }

    @Test fun mapsWebSearchNotEnabledAndCreditErrors() {
        enqueueJson(400, error("invalid_request_error", "Web search is not enabled for this organization."))
        assertEquals(ResearchError.WebSearchNotEnabled, (research() as ResearchResponse.Failure).error)
        enqueueJson(400, error("invalid_request_error", "Your credit balance is too low to access the Anthropic API."))
        assertEquals(ResearchError.OutOfCredit, (research() as ResearchResponse.Failure).error)
        enqueueJson(400, error("invalid_request_error", "messages: something else"))
        assertEquals(ResearchError.BadRequest(400), (research() as ResearchResponse.Failure).error)
    }

    @Test fun mapsAuthRateLimitAndServerErrors() {
        enqueueJson(401, error("authentication_error"))
        assertEquals(ResearchError.Unauthorized, (research() as ResearchResponse.Failure).error)
        enqueueJson(403, error("permission_error"))
        assertEquals(ResearchError.Forbidden, (research() as ResearchResponse.Failure).error)
        enqueueJson(429, error("rate_limit_error"), mapOf("retry-after" to "17"))
        assertEquals(ResearchError.RateLimited(17), (research() as ResearchResponse.Failure).error)
        enqueueJson(529, error("overloaded_error"))
        assertEquals(ResearchError.ServerError(529), (research() as ResearchResponse.Failure).error)
        enqueueJson(500, error("api_error"))
        assertEquals(ResearchError.ServerError(500), (research() as ResearchResponse.Failure).error)
        assertEquals("one request per attempt, no SDK retries", 5, server.requestCount)
    }

    @Test fun timeoutAndNetworkFailuresAreReportedNotRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val f = research(provider(Duration.ofMillis(600))) as ResearchResponse.Failure
        assertEquals("the legacy flow names its own 60 s-class limit (here 0 s after rounding 600 ms)", ResearchError.Timeout(0), f.error)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val n = research() as ResearchResponse.Failure
        assertTrue(n.error.toString(), n.error == ResearchError.Network || n.error is ResearchError.Timeout)
    }

    @Test fun cancellationAbortsTheCallAndPropagates() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val p = provider(Duration.ofSeconds(30))
        val job = async { p.research(request, FAKE_KEY, AiConfig.DEFAULT_MODEL) }
        server.takeRequest(5, TimeUnit.SECONDS)
        job.cancel()
        withTimeout(5_000) { runCatching { job.await() } }
        assertTrue(job.isCancelled)
    }
}
