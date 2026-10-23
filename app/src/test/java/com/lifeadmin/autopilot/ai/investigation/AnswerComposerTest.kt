package com.lifeadmin.autopilot.ai.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RES-080–087: answer leads, structure follows the ask, caveats stay in the main answer, judgments are never cited, nothing leaks. */
class AnswerComposerTest {
    private val now = 1_800_000_000_000L
    private fun ev(n: Int) = EvidenceRef(n, "e$n", retrievedAtMs = now, originKey = "o$n")
    private val fact = Claim("c1", "The fee is $130.", ClaimType.FACT, loadBearing = true, support = listOf(ev(1)), status = ClaimStatus.ESTABLISHED, turnId = "t")
    private val gap = Claim("c2", "Processing takes six weeks.", ClaimType.FACT, loadBearing = true, status = ClaimStatus.NOT_ESTABLISHED, turnId = "t")
    private val judgment = Claim("j1", "Apply by mail.", ClaimType.RECOMMENDATION, loadBearing = false, premises = listOf("c1"), status = ClaimStatus.ESTABLISHED, turnId = "t")
    private fun objective(kind: AskKind) = Objective("q", "o", kind)

    private fun compose(kind: AskKind, out: TurnOutput, claims: List<Claim> = listOf(fact, gap, judgment)) =
        AnswerComposer.compose(objective(kind), out, claims, ResultDeriver.derive(claims, TurnEnding.COMPLETE), "Fallback")

    @Test fun markersStayOnlyOnCitableClaimsAndInternalResidueIsRemoved() {
        val out = TurnOutput(answer = listOf(AnswerSegment("The fee is $130 [c1] so apply by mail [j1] (## ANSWER status=answered r-i0).", listOf("c1", "j1", "c9"))))
        val a = compose(AskKind.FACT, out)
        assertEquals(listOf("c1"), a.lead.first().claimIds)
        assertTrue(LeakageScan.scan(a.lead.first().text).isEmpty())
        assertFalse(a.lead.first().text.contains("r-i0"))
    }

    @Test fun everyLoadBearingGapBecomesACaveatInTheMainAnswer() {
        val a = compose(AskKind.FACT, TurnOutput(answer = listOf(AnswerSegment("The fee is $130.", listOf("c1")))))
        assertEquals(listOf("Not established: Processing takes six weeks."), a.caveats)
        assertTrue(a.stateLine.startsWith("Partly answered"))
    }

    @Test fun absenceOfEvidenceIsNeverShownAsFalse() {
        val claims = listOf(gap)
        val a = AnswerComposer.compose(objective(AskKind.FACT), TurnOutput(answer = listOf(AnswerSegment("A six-week service does not exist and there is no such fee.", listOf("c2")))), claims, ResultDeriver.derive(claims, TurnEnding.COMPLETE), "F")
        assertEquals("A six-week service could not be found and no confirmed fee.", a.lead.first().text)
        // With established support the wording is the model's own.
        val b = AnswerComposer.compose(objective(AskKind.FACT), TurnOutput(answer = listOf(AnswerSegment("There is no fee for children under 16.", listOf("c1")))), listOf(fact), ResultDeriver.derive(listOf(fact), TurnEnding.COMPLETE), "F")
        assertEquals("There is no fee for children under 16.", b.lead.first().text)
    }

    @Test fun structureFollowsTheAskNotWhatCameBack() {
        val rows = listOf(CompareRow("Price", mapOf("A" to AnswerSegment("$1", listOf("c1")), "B" to AnswerSegment("$2"))))
        val rec = Recommendation("Pick A", optional = false)
        val out = TurnOutput(answer = listOf(AnswerSegment("Lead.")), compareRows = rows, compareOptions = listOf("A", "B"), recommendation = rec, steps = listOf(AnswerSegment("Step one")))
        val fact = compose(AskKind.FACT, out)
        assertTrue("a fact question gets no comparison table", fact.sections.none { it.kind == SectionKind.COMPARISON })
        assertNull("a fact question gets no recommendation", fact.recommendation)
        assertTrue("steps only when asked", fact.sections.none { it.kind == SectionKind.STEPS })
        val compare = compose(AskKind.COMPARE, out)
        assertTrue(compare.sections.any { it.kind == SectionKind.COMPARISON && it.options == listOf("A", "B") })
        assertEquals("on a comparison a pick is kept only as the model's separate optional addition", "Pick A", compare.recommendation?.text)
        val procedure = compose(AskKind.PROCEDURE, out)
        assertTrue(procedure.sections.any { it.kind == SectionKind.STEPS })
    }

    @Test fun conditionsNotCheckedAndChangesAreTheirOwnSections() {
        val cond = Claim("k1", "Depends on your p95 target.", ClaimType.INFERENCE, loadBearing = true, dependsOnUser = true, status = ClaimStatus.DEPENDS_ON_INPUT, turnId = "t")
        val unchecked = Claim("u1", "Shipping cost.", ClaimType.FACT, loadBearing = true, status = ClaimStatus.NOT_ESTABLISHED, turnId = "t", checked = false)
        val claims = listOf(fact, cond, unchecked)
        val d = ResultDeriver.derive(claims, TurnEnding.STOPPED)
        val out = TurnOutput(answer = listOf(AnswerSegment("Lead [c1]", listOf("c1"))), changes = listOf(ClaimChange("c0", "c1", ChangeKind.CHANGED, "The fee rose from $110 to $130."), ClaimChange("c7", null, ChangeKind.CONFIRMED, "Still six weeks.")))
        val a = AnswerComposer.compose(objective(AskKind.FACT), out, claims, d, "F")
        assertEquals(listOf(SectionKind.CONDITIONS, SectionKind.CHANGES, SectionKind.CONFIRMED, SectionKind.NOT_CHECKED), a.sections.map { it.kind })
        assertTrue(a.caveats.any { it.startsWith("Not checked before it stopped: Shipping cost") })
        assertTrue(a.stateLine.startsWith("Stopped early"))
    }

    @Test fun titleFallsBackToTheObjectiveNeverToATemplate() {
        val a = compose(AskKind.FACT, TurnOutput(title = "## TITLE status=x", answer = listOf(AnswerSegment("x"))))
        assertTrue(LeakageScan.scan(a.title).isEmpty())
        assertEquals("Find out the fee", AnswerComposer.fallbackTitle(Objective("what is the fee?", "find out the fee", AskKind.FACT)))
    }

    @Test fun leakageScanFindsEveryKindOfResidue() {
        val found = LeakageScan.scan("See r-i0 and [c1] and NOT_ESTABLISHED and ## ANSWER and status=answered and src=s3")
        assertEquals(6, found.size)
        assertTrue(LeakageScan.scan("Vitamin C and $130 by Friday are fine.").isEmpty())
    }
}
