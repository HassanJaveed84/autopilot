package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.context.TurnConfig
import com.lifeadmin.autopilot.ai.context.TurnPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N2 (`docs/live-eval/R-N-a-2026-10-24-rerun.md`): one call's share of the search budget is not the investigation's
 * limit. The prompt says so, and a reply's remark about its own allowance does not reach the answer.
 */
class SearchBudgetHonestyTest {
    @Test fun theRerunsRemarkAboutItsOneSearchAllowanceIsNotShown() {
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-rerun.replies.json")
        val at = replay.ranAt.toEpochMilli()
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val x = replay.exchanges.single()
        work.merge(InvestigationParser.parse(x.reply.segments, x.reply.searches, registry, "t1", at), at)
        work.verifyRan = true
        val r = work.result(ObjectivePreparer.local(replay.question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, at)
        val unresolved = r.answer.sections.filter { it.kind == SectionKind.UNRESOLVED }.flatMap { it.items }.map { it.text }
        assertTrue(unresolved.toString(), unresolved.none { it.contains("allowance", ignoreCase = true) || it.contains("one search", ignoreCase = true) })
        assertTrue("the real limitation stays", unresolved.any { it.contains("no explicit date") })
    }

    @Test fun theFirstCallIsToldItsShareIsNotTheInvestigationsBudget() {
        val payload = TurnPayload(objective = "Find out the address", config = TurnConfig(model = "m", depth = "standard", searchBudget = 3))
        val p = InvestigationPrompt.gather(payload, ObjectivePreparer.local("What is the address of the city museum in Lisbon?"), 1, 1, false)
        assertTrue(p, p.contains("Searches allowed on this call: 1 of the 3 authorized"))
        assertFalse(p, p.contains("Searches allowed on this call: 1. "))
    }

    @Test fun remarksAboutAnAllowanceAreRemovedAndTheRestOfTheSentenceKept() {
        assertEquals("The pages give no explicit date.", CallNarration.withoutBudgetTalk("The pages give no explicit date, and the search allowance was one search."))
        assertEquals("Hours are not listed; the city site is down.", CallNarration.withoutBudgetTalk("Hours are not listed; the city site is down; I was limited to one search."))
        assertEquals("", CallNarration.withoutBudgetTalk("I could only run one search on this call."))
        assertEquals("unrelated wording is untouched", "Searching the archive found two editions.", CallNarration.withoutBudgetTalk("Searching the archive found two editions."))
    }
}
