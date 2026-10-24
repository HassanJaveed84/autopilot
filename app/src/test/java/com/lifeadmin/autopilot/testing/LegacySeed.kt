package com.lifeadmin.autopilot.testing

import com.lifeadmin.autopilot.ai.context.TurnConfig
import com.lifeadmin.autopilot.ai.investigation.Answer
import com.lifeadmin.autopilot.ai.investigation.AnswerSegment
import com.lifeadmin.autopilot.ai.investigation.AskKind
import com.lifeadmin.autopilot.ai.investigation.Authorization
import com.lifeadmin.autopilot.ai.investigation.Claim
import com.lifeadmin.autopilot.ai.investigation.ClaimStatus
import com.lifeadmin.autopilot.ai.investigation.ClaimType
import com.lifeadmin.autopilot.ai.investigation.EvidenceRef
import com.lifeadmin.autopilot.ai.investigation.Investigation
import com.lifeadmin.autopilot.ai.investigation.InvestigationMeta
import com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin
import com.lifeadmin.autopilot.ai.investigation.Objective
import com.lifeadmin.autopilot.ai.investigation.ProposedChange
import com.lifeadmin.autopilot.ai.investigation.ProposedKind
import com.lifeadmin.autopilot.ai.investigation.ResultState
import com.lifeadmin.autopilot.ai.investigation.Turn
import com.lifeadmin.autopilot.ai.investigation.TurnEnding
import com.lifeadmin.autopilot.ai.investigation.TurnKind
import com.lifeadmin.autopilot.ai.investigation.TurnResult
import com.lifeadmin.autopilot.ai.investigation.TurnState
import com.lifeadmin.autopilot.ai.investigation.TurnUsage
import com.lifeadmin.autopilot.ai.research2.SourceRef2
import com.lifeadmin.autopilot.data.repository.InvestigationRepository

/** Research saved by the retired M4 engine, written in its own format, for legacy-read tests. */
object LegacySeed {
    const val ID = "legacy-m4-thread"
    private const val AT = 1_790_000_000_000L

    fun m4Investigation(taskId: String?, running: Boolean = false): Investigation {
        val src = SourceRef2(1, "https://www.amtrak.example/stations/was", "Washington Union Station | Amtrak", "amtrak.example", false, "October 2026", listOf("50 Massachusetts Ave NE"), citedCount = 1)
        val claim = Claim(
            "c1", "The station is at 50 Massachusetts Ave NE.", ClaimType.FACT, loadBearing = true, turnId = "legacy-turn-0", status = ClaimStatus.ESTABLISHED,
            support = listOf(EvidenceRef(1, "Amtrak lists the address.", retrievedAtMs = AT, originKey = "amtrak.example", quote = "50 Massachusetts Ave NE")),
        )
        val result = TurnResult(
            Answer("Union Station, Washington DC", "Answered", listOf(AnswerSegment("Union Station is at 50 Massachusetts Ave NE, Washington, DC.", listOf("c1")))),
            ResultState.ANSWERED, TurnEnding.COMPLETE, listOf(claim), listOf(src),
            proposals = if (taskId != null) listOf(ProposedChange("p1", ProposedKind.NOTE, "Station: 50 Massachusetts Ave NE", claimIds = listOf("c1"))) else emptyList(),
            finishedAtMs = AT + 9_000,
        )
        val turn = Turn(
            "legacy-turn-0", 0, TurnKind.INITIAL, if (running) TurnState.RUNNING else TurnState.DONE, AT,
            Objective("address of Union Station, Washington, DC", "Find the address", AskKind.FACT),
            authorization = Authorization(AT, "Find the address", config = TurnConfig("claude-sonnet-5-5", depth = "standard", searchBudget = 3), presetName = "Standard", payloadJson = "{}", payloadSha256 = "0"),
            result = result, usage = TurnUsage(webSearches = 1, spentMicros = 40_000),
        )
        return Investigation(ID, if (taskId != null) InvestigationOrigin.TASK else InvestigationOrigin.STANDALONE, taskId, null, AT, AT + 9_000, InvestigationMeta("Union Station, Washington DC", "address of Union Station, Washington, DC"), listOf(turn))
    }

    suspend fun m4Thread(repo: InvestigationRepository, taskId: String?, running: Boolean = false): String {
        repo.save(m4Investigation(taskId, running))
        return ID
    }
}
