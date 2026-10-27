package com.lifeadmin.autopilot.testing

import com.lifeadmin.autopilot.ai.aifirst.AfAuthorization
import com.lifeadmin.autopilot.ai.aifirst.AfConfig
import com.lifeadmin.autopilot.ai.aifirst.AfSearch
import com.lifeadmin.autopilot.ai.aifirst.AfSegment
import com.lifeadmin.autopilot.ai.aifirst.AfSource
import com.lifeadmin.autopilot.ai.aifirst.AfStatus
import com.lifeadmin.autopilot.ai.aifirst.AfTurn
import com.lifeadmin.autopilot.ai.research.BriefContent
import com.lifeadmin.autopilot.ai.research.Finding
import com.lifeadmin.autopilot.ai.research.MoveKind
import com.lifeadmin.autopilot.ai.research.NextMove
import com.lifeadmin.autopilot.ai.research.ResearchUsage
import com.lifeadmin.autopilot.ai.research.Segment
import com.lifeadmin.autopilot.ai.research.Side
import com.lifeadmin.autopilot.ai.research.SourceRef
import com.lifeadmin.autopilot.ai.research.SourceTier
import com.lifeadmin.autopilot.ai.research2.SessionContent
import com.lifeadmin.autopilot.data.local.UsageRecordEntity
import java.time.Instant

/**
 * Research as earlier versions with built-in AI saved it, for tests of the read-only screens,
 * backups and migrations. Built directly from the stored models: nothing here calls a provider.
 */
object SavedResearchFixtures {
    /** An M3A brief with every finding type and two sources. */
    val brief = BriefContent(
        quickAnswer = listOf(Segment("Advance tickets go on sale 12 weeks ahead.", listOf(1))),
        moves = listOf(NextMove("m1", MoveKind.STEP, "Check the release date", "12 weeks out", listOf(1)), NextMove("m2", MoveKind.CHECK, "Confirm seat reservation", "nothing covered it")),
        findings = listOf(
            Finding.Sourced("Fares are released 12 weeks ahead.", listOf(1), SourceTier.OFFICIAL),
            Finding.Disagree("Release window", listOf(Side(1, "12 weeks"), Side(2, "10 weeks")), "Confirm with the operator."),
            Finding.Unconfirmed("Child fares", "no page covered it"),
            Finding.Suggestion("Cheapest at release."),
        ),
        sources = listOf(
            SourceRef(1, "https://www.northlinerail.example/tickets", "Tickets", "northlinerail.example", SourceTier.OFFICIAL, "September 2026", listOf("released 12 weeks ahead"), 3),
            SourceRef(2, "https://travelblog.example/tips", "Tips", "travelblog.example", SourceTier.WEB, null, listOf("10 weeks"), 1),
        ),
        queries = listOf("northline advance tickets"),
        usage = ResearchUsage(1000, 300, 1, "claude-haiku-5-5", 3000),
        verified = true,
        truncated = false,
        injectionNoticed = false,
        shared = listOf("Task title", "Your question"),
    )

    /** A finished Research Online (AI-first) turn with one cited passage, as the retired engine stored it. */
    fun answeredTurn(id: String, index: Int, atMs: Long, question: String = "When do advance tickets go on sale?") = AfTurn(
        id = id, index = index, question = question, createdAtMs = atMs, status = AfStatus.COMPLETE,
        authorization = AfAuthorization(
            question = question, config = AfConfig("claude-sonnet-5-5", 3, 100), promptVersion = 2,
            payload = question, payloadSha256 = "0".repeat(64), authorizedAtMs = atMs,
        ),
        segments = listOf(AfSegment("Advance tickets go on sale 12 weeks before travel.", listOf(1)), AfSegment(" Check the date with the operator.")),
        answer = "Advance tickets go on sale 12 weeks before travel. [1] Check the date with the operator.",
        sources = listOf(AfSource(1, "https://www.northlinerail.example/tickets", "Tickets | Northline Rail", listOf("released 12 weeks ahead"), "September 2026")),
        searches = listOf(AfSearch("northline advance tickets", 4)),
        calls = 1, webSearches = 1, costMicros = 42_000, startedAtMs = atMs, finishedAtMs = atMs + 30_000,
    )

    /** The request behind [m3a2Session], in the person's words. */
    const val SUPPLEMENTS_REQUEST = "Brand preference: Thorne and Pure Encapsulations. Need a general multivitamin, vitamin D, magnesium and glucosamine. Produce a list, each as a separate step added to my task."

    /**
     * A real M3A.2 session payload (four item requirements, candidates, picks, cited sources), as the
     * retired engine produced it from its scripted supplements fixture. Generated once from that code
     * and kept as JSON, so it is exactly what an earlier version stored.
     */
    fun m3a2Session(): SessionContent {
        val raw = requireNotNull(SavedResearchFixtures::class.java.getResource("/fixtures/legacy/m3a2-supplements-session.json")).readText()
        return kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "kind" }.decodeFromString(SessionContent.serializer(), raw)
    }

    /** One `usage_records` row as the retired AI features wrote it (counts and an estimate, never text). */
    fun usageRow(
        id: String, feature: String, model: String, startedAt: Instant, outcome: String, usageStatus: String,
        sessionId: String? = null, taskId: String? = null, runId: String? = null, stage: String? = null,
        inputTokens: Long? = null, outputTokens: Long? = null, webSearches: Int? = null, estimatedMicros: Long? = null, providerMessageId: String? = null,
    ) = UsageRecordEntity(
        id, feature, model, startedAt, runId, sessionId, taskId, stage, durationMs = null, inputTokens = inputTokens, outputTokens = outputTokens,
        cacheWrite5mTokens = if (inputTokens != null) 0 else null, cacheWrite1hTokens = if (inputTokens != null) 0 else null, cacheReadTokens = if (inputTokens != null) 0 else null,
        webSearches = webSearches, outcome = outcome, usageStatus = usageStatus, estimatedMicros = estimatedMicros, priceVersion = "2026-10", providerMessageId = providerMessageId,
    )
}
