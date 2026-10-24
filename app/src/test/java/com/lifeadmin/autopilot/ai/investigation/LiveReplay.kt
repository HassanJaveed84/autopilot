package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/**
 * Provider replies recorded by a paid live evaluation (`docs/live-eval/`), replayed offline. The
 * fixture is extracted from the sanitized report, so each reply carries its real text blocks,
 * structured citations and search results exactly as the adapter returned them.
 */
class LiveReplay private constructor(val question: String, val ranAt: Instant, val exchanges: List<Exchange>) {
    class Exchange(val stage: String, val user: String, val reply: CallResult.Done)

    companion object {
        fun load(resource: String): LiveReplay {
            val text = LiveReplay::class.java.classLoader!!.getResourceAsStream(resource)!!.bufferedReader().readText()
            val root = Json.parseToJsonElement(text).jsonObject
            val exchanges = root["exchanges"]!!.jsonArray.map { e ->
                val x = e.jsonObject
                val u = x["usage"]!!.jsonObject
                val segments = x["segments"]!!.jsonArray.map { s ->
                    val so = s.jsonObject
                    RawSegment(so.str("text")!!, so["citations"]!!.jsonArray.map { c -> c.jsonObject.let { RawCitation(it.str("url")!!, it.str("title"), it.str("citedText")!!) } })
                }
                val searches = x["searches"]!!.jsonArray.map { q ->
                    val qo = q.jsonObject
                    RawSearch(qo.str("query")!!, qo["results"]!!.jsonArray.map { r -> r.jsonObject.let { RawSearchResult(it.str("url")!!, it.str("title")!!, it.str("pageAge")) } })
                }
                val usage = MeasuredUsage(u.num("inputTokens"), u.num("outputTokens"), u.num("cacheWriteTokens"), 0, u.num("cacheReadTokens"), u.int("webSearches"))
                Exchange(x.str("stage")!!, x.str("user")!!, CallResult.Done(segments, searches, usage, CallStop.END, x["elapsedMs"]!!.jsonPrimitive.long, null))
            }
            return LiveReplay(root.str("question")!!, Instant.parse(root.str("ranAtUtc")!!), exchanges)
        }

        private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.num(k: String): Long? = this[k]?.jsonPrimitive?.long
        private fun JsonObject.int(k: String): Int? = this[k]?.jsonPrimitive?.int
    }
}
