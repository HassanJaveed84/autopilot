package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.context.ContextItem
import com.lifeadmin.autopilot.ai.context.FindingDisposition
import com.lifeadmin.autopilot.ai.context.InheritedFinding
import com.lifeadmin.autopilot.ai.context.InvestigationTurnBuilder
import com.lifeadmin.autopilot.ai.context.TurnInput
import com.lifeadmin.autopilot.ai.context.TurnPayload
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Decides, per earlier claim, what a continuation does with it (RES-100–104, OD-4): excluded stays
 * excluded, volatile is checked fresh, what the new objective answers is replaced, what it needs is
 * sent as found, the rest is not sent. Pure and deterministic; the authorization surface shows the
 * outcome and the frozen payload carries only the SENT items.
 */
object InheritancePlanner {
    fun plan(objective: Objective, claims: List<Claim>, excluded: Set<String>, suppliedDetails: List<String> = emptyList()): List<InheritedFinding> {
        val words = significantWords(objective.text + " " + objective.question + " " + objective.constraints.joinToString(" ") { it.text })
        val supplied = significantWords(suppliedDetails.joinToString(" "))
        return claims.filter { !it.superseded }.map { c ->
            val cw = significantWords(c.text + " " + c.scope)
            val overlap = cw.count { it in words }
            val disposition: FindingDisposition
            val reason: String
            when {
                c.id in excluded -> { disposition = FindingDisposition.EXCLUDED; reason = "Excluded by you" }
                c.dependsOnUser && supplied.isNotEmpty() && cw.count { it in supplied } >= 1 -> { disposition = FindingDisposition.REPLACED; reason = "Answered by what you added" }
                c.volatile -> { disposition = FindingDisposition.FRESH; reason = "Changes quickly; checked fresh, never reused" }
                c.loadBearing || overlap >= 1 -> { disposition = FindingDisposition.SENT; reason = if (overlap >= 1) "Relevant to the new objective" else "The earlier answer rests on it" }
                else -> { disposition = FindingDisposition.NOT_SENT; reason = "Not needed for this objective" }
            }
            InheritedFinding(c.id, c.text, ClaimAssessor.label(c.status), c.asOf, disposition, reason)
        }
    }

    internal fun significantWords(text: String): Set<String> = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in STOP }.toSet()

    private val STOP = setOf("what", "which", "that", "this", "with", "from", "have", "does", "should", "would", "could", "about", "their", "there", "where", "when", "will", "your", "find", "best", "into", "than", "them", "they", "been", "were", "also", "only", "most", "much", "many", "more", "some", "then", "need", "needs", "want")
}

/**
 * Builds the inspectable authorization record (RES-021, RES-160/161, handoff §6): the objective and
 * constraints, every context item with its included flag, every earlier finding with its disposition,
 * the exact frozen payload and fingerprint, the configuration, the estimate and the warnings. Written
 * to the turn before any provider call; the run refuses to start without it.
 */
object AuthorizationBuilder {
    private val json = Json { ignoreUnknownKeys = true }

    fun build(objective: Objective, context: List<ContextItem>, inherited: List<InheritedFinding>, config: ResearchRunConfig, nowMs: Long): Authorization {
        val input = TurnInput(
            objective = objective.text.ifBlank { objective.question },
            constraints = objective.constraints.map { it.text },
            context = context,
            inherited = inherited,
            config = config.turnConfig(),
        )
        val payload = InvestigationTurnBuilder.build(input)
        val estimate = config.estimate()
        val warnings = InvestigationTurnBuilder.warnings(input) + listOfNotNull(ResearchPresets.insufficientLevelWarning(config.depth, objective))
        return Authorization(
            approvedAtMs = nowMs, objectiveText = input.objective, constraints = input.constraints,
            context = context.map { ContextRecord(it.id, it.label, it.value, it.included, it.requestOnly) },
            inherited = inherited.map { InheritedRecord(it.id, it.text, it.disposition, it.reason) },
            freshChecks = inherited.filter { it.disposition == FindingDisposition.FRESH }.map { it.text },
            config = config.turnConfig(), presetName = config.presetName,
            payloadJson = payload.json, payloadSha256 = payload.sha256, payloadLines = payload.lines,
            estimateLowMicros = estimate?.lowMicros, estimateHighMicros = estimate?.highMicros,
            warnings = warnings, disclosedGaps = objective.disclosedGaps.map { it.text },
        )
    }

    /** The payload exactly as authorized; the run prompts are built from this and nothing else. */
    fun payload(a: Authorization): TurnPayload = json.decodeFromString(TurnPayload.serializer(), a.payloadJson)
}
