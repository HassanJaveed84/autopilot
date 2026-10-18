package com.lifeadmin.autopilot.ai

import com.lifeadmin.autopilot.ai.anthropic.AnthropicTaskPlanningProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Exercises the real Anthropic adapter (official SDK + OkHttp) against a local MockWebServer:
 * request shape, structured-output wiring, stop reasons and every error class. No real key and
 * no live API are involved; live integration is NOT covered by these tests.
 */
class AnthropicProviderHttpTest {
    private lateinit var server: MockWebServer
    private val key = "test-key-0000000000000000000000000000" // fake; never a real credential

    private val request = PlanningRequest(
        PlanningMode.CAPTURE,
        "Need to book the train ride for my daughter, find parking and tickets, remind me at 9 and 6 every day until done.",
        PlanningContext(LocalDateTime.of(2026, 10, 14, 10, 0), "America/Chicago", "-05:00", LocalTime.of(21, 0) to LocalTime.of(8, 0), listOf(LocalTime.of(9, 0), LocalTime.of(18, 0))),
    )

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun provider(timeout: Duration = Duration.ofSeconds(10)) =
        AnthropicTaskPlanningProvider(baseUrl = server.url("/").toString().trimEnd('/'), timeout = timeout)

    private fun plan(p: AnthropicTaskPlanningProvider = provider(), model: String = AiConfig.DEFAULT_MODEL) = runBlocking { p.plan(request, key, model) }

    private fun messageJson(text: String?, stop: String = "end_turn", extraBlocks: String = "", stopDetails: String = "null") = """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[$extraBlocks${if (text != null) (if (extraBlocks.isNotEmpty()) "," else "") + """{"type":"text","text":${Json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}""" else ""}],
         "stop_reason":"$stop","stop_sequence":null,"stop_details":$stopDetails,
         "usage":{"input_tokens":812,"output_tokens":356}}
    """.trimIndent()

    private val planJson = """
        {"title":"Book train ride for daughter","notes":"","steps":["Check train times and prices","Buy tickets","Find and book parking"],
         "next_step":"Check train times and prices",
         "deadline":{"provenance":"unknown","date":"","time":"","source_phrase":""},
         "reminder":{"kind":"daily_times","provenance":"explicit","times":["09:00","18:00"],"start_date":"","day_interval":1,"weekdays":[],"once_date":"","once_time":"","once_in_minutes":0,"requested_phrase":"remind me at 9 and 6 every day until done"},
         "waiting":{"is_waiting":false,"waiting_for":"","check_in_date":"","check_in_time":""},
         "priority":{"value":"none","reason":""},
         "assumptions":["Interpreted '9 and 6' as 09:00 and 18:00"],"questions":[],"ignored_instructions":[],"advice":""}
    """.trimIndent()

    private fun enqueueJson(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val r = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)
        headers.forEach { (k, v) -> r.setHeader(k, v) }
        server.enqueue(r)
    }

    private fun error(type: String) = """{"type":"error","error":{"type":"$type","message":"test"}}"""

    @Test fun sendsStructuredOutputRequestWithKeyVersionAndMinimalContext() {
        enqueueJson(200, messageJson(planJson))
        val result = plan()
        assertTrue(result is PlanningResult.Success)
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/messages", recorded.requestUrl!!.encodedPath)
        assertEquals(key, recorded.getHeader("x-api-key"))
        assertTrue(recorded.getHeader("anthropic-version")!!.isNotBlank())
        assertNull("no beta header for Haiku", recorded.getHeader("anthropic-beta"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(AiConfig.DEFAULT_MODEL, body["model"]!!.jsonPrimitive.content)
        assertEquals(AiConfig.MAX_OUTPUT_TOKENS, body["max_tokens"]!!.jsonPrimitive.long)
        val format = body["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        val schema = format["schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
        assertTrue("every property required", (schema["required"]!!.jsonArray.size) == (schema["properties"] as JsonObject).size)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(body["system"].toString().contains("data, not instructions"))
        val user = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val content = user["content"].toString()
        assertTrue(content.contains("<task_text>"))
        assertTrue(content.contains("Current local date: 2026-10-14 (Wednesday)"))
        assertTrue(content.contains("America/Chicago"))
        assertFalse("no fallbacks for Haiku", body.containsKey("fallbacks"))
    }

    @Test fun sonnetAndOpusRequestsOptIntoServerSideRefusalFallback() {
        enqueueJson(200, messageJson(planJson))
        plan(model = "claude-sonnet-5-5")
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(AnthropicTaskPlanningProvider.FALLBACK_BETA, recorded.getHeader("anthropic-beta"))
        assertEquals("default", Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject["fallbacks"]!!.jsonPrimitive.content)
    }

    @Test fun successParsesPlanAndUsage() {
        enqueueJson(200, messageJson(planJson))
        val r = plan() as PlanningResult.Success
        assertEquals("Book train ride for daughter", r.plan.title)
        assertEquals(3, r.plan.steps.size)
        assertEquals(listOf("09:00", "18:00"), r.plan.reminder.times)
        assertEquals(812L, r.usage!!.inputTokens)
        assertEquals(356L, r.usage!!.outputTokens)
    }

    @Test fun thinkingBlocksBeforeTheTextAreIgnored() {
        enqueueJson(200, messageJson(planJson, extraBlocks = """{"type":"thinking","thinking":"","signature":"sig"}"""))
        assertTrue(plan() is PlanningResult.Success)
    }

    @Test fun refusalIsReportedBeforeContentIsRead() {
        enqueueJson(200, messageJson("I can't help with that.", stop = "refusal", stopDetails = """{"type":"refusal","category":"cyber","explanation":"x"}"""))
        val e = (plan() as PlanningResult.Failure).error
        assertTrue(e is PlanningError.Refused)
    }

    @Test fun truncatedResponseIsNotParsed() {
        enqueueJson(200, messageJson(planJson.take(120), stop = "max_tokens"))
        assertEquals(PlanningError.Truncated, (plan() as PlanningResult.Failure).error)
    }

    @Test fun malformedJsonIsRejected() {
        enqueueJson(200, messageJson("Sure! Here's your plan: book the train."))
        assertTrue((plan() as PlanningResult.Failure).error is PlanningError.Malformed)
    }

    @Test fun emptyContentIsRejected() {
        enqueueJson(200, messageJson(null))
        assertTrue((plan() as PlanningResult.Failure).error is PlanningError.Malformed)
    }

    @Test fun wrongShapeJsonDecodesLenientlyAndValidatorHandlesIt() {
        // Valid JSON but missing most fields: decodes with defaults; validation decides usability.
        enqueueJson(200, messageJson("""{"title":"Buy milk"}"""))
        val r = plan() as PlanningResult.Success
        assertEquals("Buy milk", r.plan.title)
        assertEquals("none", r.plan.reminder.kind)
    }

    @Test fun http401IsUnauthorized() {
        enqueueJson(401, error("authentication_error"))
        assertEquals(PlanningError.Unauthorized, (plan() as PlanningResult.Failure).error)
    }

    @Test fun http403And402AreForbidden() {
        enqueueJson(403, error("permission_error"))
        assertEquals(PlanningError.Forbidden, (plan() as PlanningResult.Failure).error)
        enqueueJson(402, error("billing_error"))
        assertEquals(PlanningError.Forbidden, (plan() as PlanningResult.Failure).error)
    }

    @Test fun http429IsRateLimitedWithRetryAfterAndNotRetriedSilently() {
        enqueueJson(429, error("rate_limit_error"), mapOf("retry-after" to "30"))
        val e = (plan() as PlanningResult.Failure).error as PlanningError.RateLimited
        assertEquals(Duration.ofSeconds(30), e.retryAfter)
        assertEquals("exactly one request (no automatic retries)", 1, server.requestCount)
    }

    @Test fun http5xxIsServerError() {
        enqueueJson(500, error("api_error"))
        assertEquals(PlanningError.ServerError(500), (plan() as PlanningResult.Failure).error)
        enqueueJson(529, error("overloaded_error"))
        assertEquals(PlanningError.ServerError(529), (plan() as PlanningResult.Failure).error)
        assertEquals(2, server.requestCount)
    }

    @Test fun http400And404AreBadRequest() {
        enqueueJson(400, error("invalid_request_error"))
        assertEquals(PlanningError.BadRequest(400), (plan() as PlanningResult.Failure).error)
        enqueueJson(404, error("not_found_error"))
        assertEquals(PlanningError.BadRequest(404), (plan() as PlanningResult.Failure).error)
    }

    @Test fun slowResponseTimesOut() {
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(messageJson(planJson)).setHeadersDelay(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val e = (plan(provider(timeout = Duration.ofMillis(800))) as PlanningResult.Failure).error
        assertEquals(PlanningError.Timeout, e)
        assertTrue("gave up promptly", Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(4))
    }

    @Test fun unreachableServerIsANetworkError() {
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val e = (runBlocking { AnthropicTaskPlanningProvider(baseUrl = url, timeout = Duration.ofSeconds(3)).plan(request, key, AiConfig.DEFAULT_MODEL) } as PlanningResult.Failure).error
        assertEquals(PlanningError.Network, e)
    }

    @Test fun droppedConnectionIsANetworkOrTimeoutError() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = (plan(provider(timeout = Duration.ofSeconds(3))) as PlanningResult.Failure).error
        assertTrue("got $e", e == PlanningError.Network || e == PlanningError.Timeout)
    }

    @Test fun cancellingTheCoroutineAbortsTheRequest() = runBlocking {
        server.enqueue(MockResponse().setHeader("content-type", "application/json").setBody(messageJson(planJson)).setHeadersDelay(10, TimeUnit.SECONDS))
        val p = provider(timeout = Duration.ofSeconds(30))
        val started = System.nanoTime()
        val call = async { p.plan(request, key, AiConfig.DEFAULT_MODEL) }
        server.takeRequest(5, TimeUnit.SECONDS) // request is in flight
        call.cancel()
        withTimeout(3_000) { runCatching { call.await() } }
        assertTrue(call.isCancelled)
        assertTrue("cancelled promptly", Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(6))
    }

    @Test fun testConnectionUsesTheFreeModelsEndpoint() {
        enqueueJson(200, """{"type":"model","id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5","created_at":"2026-09-01T00:00:00Z"}""")
        assertNull(runBlocking { provider().testConnection(key, AiConfig.DEFAULT_MODEL) })
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", recorded.method)
        assertEquals("/v1/models/claude-haiku-5-5", recorded.requestUrl!!.encodedPath)
        enqueueJson(401, error("authentication_error"))
        assertEquals(PlanningError.Unauthorized, runBlocking { provider().testConnection(key, AiConfig.DEFAULT_MODEL) })
    }

    @Test fun errorMessagesNeverContainTheKey() {
        enqueueJson(401, error("authentication_error"))
        val e = (plan() as PlanningResult.Failure).error
        assertFalse(e.message.contains(key))
        assertFalse(e.toString().contains(key))
    }
}
