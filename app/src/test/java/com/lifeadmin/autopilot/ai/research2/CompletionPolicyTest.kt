package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.testing.Research2Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardening finding A: a requirement counts as complete only when the structured result backs it.
 * Statuses are validated in code; constraints and output wishes take part; Partly and Needs a check
 * are never "covered"; the outcome and the on-screen coverage always agree.
 */
class CompletionPolicyTest {
    private val now = 1_700_000_000_000L

    private fun fresh(request: String): SessionContent {
        val r = RequirementExtractor.extract(request)
        return SessionContent("t", request, r.template, r.requirements, r.requirements.map { Coverage(it.id, RequirementStatus.PENDING) }, outcome = SessionOutcome.INTERRUPTED)
    }

    private fun merged(request: String, vararg calls: CallResult.Done, edit: (SessionContent) -> SessionContent = { it }): SessionContent {
        var s = edit(fresh(request))
        val registry = SourceRegistry2()
        for (c in calls) s = SessionAssembler.merge(s, ContractParser.parse(c.segments, c.searches, registry, s.requirements, now, s.body.candidates, SessionAssembler.evidenceLines(s)), registry.sources())
        return SessionAssembler.settlePending(s)
    }

    private fun done(vararg segs: RawSegment) = CallResult.Done(segs.toList(), emptyList(), MeasuredUsage(1_000, 100, 0, 0, 0, 0), CallStop.END, 1, "m-x")

    private val otherBrand = RawCitation("https://www.otherbrand.example/glucosamine-1500", "Glucosamine 1500 | Other Brand", "Glucosamine sulfate 1,500 mg per tablet, 90 tablets.")

    /** Second pass that finds glucosamine from a brand the person did not ask for. */
    private fun glucosamineElsewhere(status: String, brandLine: String? = null) = done(
        RawSegment("## REQ r-i3 · status=$status\n", emptyList()),
        RawSegment("Other Brand Glucosamine 1500 is listed on the brand's page.", listOf(otherBrand)),
        RawSegment("\n## PICK r-i3 · status=$status\nPick: Other Brand Glucosamine 1500\nLabel: ", emptyList()),
        RawSegment("Glucosamine sulfate 1,500 mg per tablet, 90 tablets.", listOf(otherBrand)),
        RawSegment("\nWhy: The only glucosamine product found.\n" + (brandLine?.let { "## REQ r-c0 · status=answered\n$it\n" } ?: ""), emptyList()),
    )

    private fun fragranceWith(c2Price: String): CallResult.Done {
        val fx = Research2Fixtures.fragranceGather()
        return fx.copy(segments = fx.segments.map { seg -> if (seg.text.startsWith("145 USD")) seg.copy(text = c2Price) else seg })
    }

    // ------------------------------------------------------------------ statuses

    @Test fun statusSemanticsAreExplicit() {
        assertTrue(RequirementStatus.ANSWERED.covered && RequirementStatus.RESOLVED_CONFLICT.covered && RequirementStatus.YOUR_CALL.covered)
        assertFalse(RequirementStatus.PARTLY.covered); assertFalse(RequirementStatus.NEEDS_CHECK.covered); assertFalse(RequirementStatus.NOT_FOUND.covered); assertFalse(RequirementStatus.PENDING.covered)
        assertTrue(RequirementStatus.entries.all { it.covered != it.open })
        assertFalse(RequirementStatus.PENDING.investigated); assertTrue(RequirementStatus.NOT_FOUND.investigated)
        assertTrue(RequirementStatus.YOUR_CALL.needsUser && RequirementStatus.NEEDS_CHECK.needsUser)
    }

    @Test fun partlyAnsweredRequirementIsAGapAndTheRunIsPartiallyComplete() {
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather(), glucosamineElsewhere("partly"))
        assertEquals(RequirementStatus.PARTLY, s.coverageOf("r-i3").status)
        assertFalse(SessionAssembler.covered(s))
        assertTrue(SessionAssembler.gaps(s).any { it.id == "r-i3" })
        assertEquals(SessionOutcome.PARTIAL, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, SessionAssembler.outcome(s, SessionAssembler.Ending.BUDGET_SEARCHES))
        assertEquals("3 of 4 ready · 1 partly", SessionAssembler.coverageLine(s))
    }

    @Test fun completelyUnansweredRequirementIsIncompleteEvidence() {
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather()) { c ->
            c.copy(requirements = c.requirements + Requirement("r-u0", RequirementType.USER_ADDED, "vegan capsules", RequirementOrigin.USER))
        }
        assertEquals(RequirementStatus.NOT_FOUND, s.coverageOf("r-i3").status)
        assertEquals("never reported → Not found after the run", RequirementStatus.NOT_FOUND, s.coverageOf("r-u0").status)
        assertEquals(SessionOutcome.INCOMPLETE_EVIDENCE, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        assertEquals(listOf("glucosamine", "vegan capsules"), SessionAssembler.gaps(s).filter { it.type != RequirementType.OUTPUT }.map { it.label })
    }

    // ------------------------------------------------------------------ constraints

    @Test fun overBudgetShortlistCandidateBreaksTheBudgetConstraint() {
        val s = merged(Research2Fixtures.FRAGRANCE_REQUEST, fragranceWith("210 USD · 50 ml · Brand House"))
        val c0 = s.coverageOf("r-c0")
        assertEquals("the model wrote status=answered; the $210 price says otherwise", RequirementStatus.PARTLY, c0.status)
        assertTrue(c0.summary, c0.summary.contains("Oud Noir") && c0.summary.contains("$210") && c0.summary.contains("$150"))
        assertTrue(s.callouts.any { it.kind == CalloutKind.BUDGET && it.text.contains("Oud Noir") })
        assertFalse(SessionAssembler.covered(s))
        assertEquals(SessionOutcome.PARTIAL, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        // The excluded $210 candidate (c4) is not a violation: it was left out.
        assertTrue(s.body.excluded.any { it.name == "Royal Iris Extrait" })
    }

    @Test fun budgetWithinLimitIsVerifiedByCodeNotByTheModel() {
        val lying = Research2Fixtures.fragranceGather().let { fx -> fx.copy(segments = fx.segments.map { seg -> seg.copy(text = seg.text.replace("## REQ r-c0 · status=answered\nAll included picks are at or under \$150 at the cited retailers.", "## REQ r-c0 · status=not_found\nCould not check the budget.")) }) }
        val s = merged(Research2Fixtures.FRAGRANCE_REQUEST, lying, Research2Fixtures.fragranceFillGaps())
        assertEquals("every pick has a confirmed price at or under $150, whatever the model wrote", RequirementStatus.ANSWERED, s.coverageOf("r-c0").status)
        assertTrue(s.coverageOf("r-c0").summary.contains("confirmed prices at or under $150"))
        // After the first pass alone Vanilla Smoke's price is unconfirmed: the budget needs a check.
        assertEquals(RequirementStatus.NEEDS_CHECK, merged(Research2Fixtures.FRAGRANCE_REQUEST, lying).coverageOf("r-c0").status)
    }

    @Test fun budgetCannotBeConfirmedWhileAPickHasNoPrice() {
        val fx = Research2Fixtures.fragranceGather()
        val noPrice = fx.copy(segments = fx.segments.map { seg -> seg.copy(text = seg.text.replace("## ATTR c3.price · tag=Fact\n79 USD · 60 ml · Big Retailer\n", "")) })
        val s = merged(Research2Fixtures.FRAGRANCE_REQUEST, noPrice)
        assertEquals(RequirementStatus.NEEDS_CHECK, s.coverageOf("r-c0").status)
        assertTrue(s.coverageOf("r-c0").summary.contains("Vanilla Smoke"))
        assertFalse(SessionAssembler.covered(s))
    }

    @Test fun exceptionThePersonAllowedIsYourCallNotAFailure() {
        val request = "Need recommendations for the best men's perfume, around \$150 in the USA, as a gift."
        val r = RequirementExtractor.extract(request)
        val budget = r.requirements.first { it.type == RequirementType.CONSTRAINT && it.label.startsWith("Budget") }
        assertTrue("'around' makes the budget a soft bound", budget.soft)
        val s = merged(request, fragranceWith("210 USD · 50 ml · Brand House"), Research2Fixtures.fragranceFillGaps())
        val c0 = s.coverageOf(budget.id)
        assertEquals(RequirementStatus.YOUR_CALL, c0.status)
        assertTrue(c0.summary, c0.summary.contains("exception") && c0.summary.contains("Oud Noir"))
        assertTrue(s.callouts.any { it.kind == CalloutKind.BUDGET && it.text.contains("Exception") })
        assertTrue(SessionAssembler.covered(s))
        assertEquals(SessionOutcome.COMPLETE, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        // A strict label edited by the person to allow exceptions behaves the same way.
        assertTrue(CompletionPolicy.isSoft(Requirement("r-c9", RequirementType.CONSTRAINT, "Budget: up to \$150 (exceptions ok)")))
        assertFalse(CompletionPolicy.isSoft(Requirement("r-c9", RequirementType.CONSTRAINT, "Budget: up to \$150, hard limit")))
    }

    @Test fun brandPreferenceNotFulfilledIsNamedAsAnExceptionEvenWhenTheModelSaysOtherwise() {
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather(), glucosamineElsewhere("answered", brandLine = "Every pick is from a preferred brand."))
        assertEquals("the item itself was found and cited", RequirementStatus.ANSWERED, s.coverageOf("r-i3").status)
        val c0 = s.coverageOf("r-c0")
        assertEquals("a preference is soft: the off-brand pick is an exception for the person", RequirementStatus.YOUR_CALL, c0.status)
        assertTrue(c0.summary, c0.summary.contains("Not from a preferred brand") && c0.summary.contains("glucosamine") && c0.summary.contains("Other Brand Glucosamine 1500"))
        assertEquals("every pick evaluated: three preferred, one exception", "4 of 4 ready", SessionAssembler.coverageLine(s))
        assertEquals(SessionOutcome.COMPLETE, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        // With only three on-brand picks the constraint is verified in code.
        val three = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather())
        assertEquals(RequirementStatus.ANSWERED, three.coverageOf("r-c0").status)
        assertTrue(three.coverageOf("r-c0").summary.contains("Thorne"))
    }

    @Test fun strictBrandRequirementBrokenIsPartlyAndTheRunIsNotComplete() {
        // The person edited the preference into a hard rule on the preflight.
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather(), glucosamineElsewhere("answered")) { c ->
            c.copy(requirements = c.requirements.map { r -> if (r.type == RequirementType.CONSTRAINT) r.copy(label = "Brand: only Thorne and Pure Encapsulations", soft = false) else r })
        }
        val c0 = s.requirements.first { it.type == RequirementType.CONSTRAINT }
        assertEquals(RequirementStatus.PARTLY, s.coverageOf(c0.id).status)
        assertFalse(SessionAssembler.covered(s))
        assertEquals(SessionOutcome.PARTIAL, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
    }

    @Test fun exclusionStillPresentInAPickIsFlagged() {
        val request = "Need recommendations for the best men's perfume, up to \$150 in the USA, as a gift. No testers."
        val r = RequirementExtractor.extract(request)
        val excl = r.requirements.first { it.label.startsWith("Exclude") }
        val fx = Research2Fixtures.fragranceGather()
        val tester = fx.copy(segments = fx.segments.map { seg -> seg.copy(text = seg.text.replace("## CAND c2 · name=Oud Noir · variant=EDP 50 ml · style=woody", "## CAND c2 · name=Oud Noir · variant=EDP 50 ml tester · style=woody")) })
        val s = merged(request, tester)
        assertEquals(RequirementStatus.PARTLY, s.coverageOf(excl.id).status)
        assertTrue(s.coverageOf(excl.id).summary.contains("Oud Noir"))
        val clean = merged(request, fx)
        assertEquals(RequirementStatus.ANSWERED, clean.coverageOf(excl.id).status)
    }

    // ------------------------------------------------------------------ output wishes and evidence

    @Test fun requestedOutputMustActuallyBeProvided() {
        // Three picks and three step proposals for four items: the "one step per item" wish is only partly met.
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather())
        assertEquals(RequirementStatus.PARTLY, s.coverageOf("r-o0").status)
        assertEquals("Steps for 3 of 4 items", s.coverageOf("r-o0").summary)
        // Acknowledged but not delivered: REQ says answered, no PICK/PROPOSE blocks at all.
        val acknowledged = done(
            RawSegment("## REQ r-o0 · status=answered\nOne proposal per item below.\n## REQ r-c0 · status=answered\nok\n## REQ r-i0 · status=answered\n", emptyList()),
            RawSegment("Basic Nutrients 2/Day is Thorne's two-capsule multivitamin.", listOf(Research2Fixtures.thorneMulti)),
            RawSegment("\n## REQ r-i1 · status=not_found\nx\n## REQ r-i2 · status=not_found\nx\n## REQ r-i3 · status=not_found\nx\n", emptyList()),
        )
        val none = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, acknowledged)
        assertEquals(RequirementStatus.NOT_FOUND, none.coverageOf("r-o0").status)
        assertFalse(SessionAssembler.covered(none))
        // A shortlist wish needs candidates and a recommendation.
        val fx = Research2Fixtures.fragranceGather()
        val noCands = fx.copy(segments = fx.segments.filterNot { it.text.contains("## CAND") || it.text.contains("## ATTR") || it.text.startsWith("98.00") || it.text.startsWith("145 USD") || it.text.startsWith("5–6") || it.text.startsWith("8+") })
        val sl = merged(Research2Fixtures.FRAGRANCE_REQUEST, noCands)
        assertEquals(RequirementStatus.NOT_FOUND, sl.coverageOf("r-o0").status)
        assertEquals(SessionOutcome.INCOMPLETE_EVIDENCE, SessionAssembler.outcome(sl, SessionAssembler.Ending.NORMAL))
    }

    @Test fun userDecisionCountsAsCompleteOnlyWithOptionsEstablished() {
        val s = merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        assertEquals(RequirementStatus.YOUR_CALL, s.coverageOf("r-c2").status)
        assertTrue(SessionAssembler.covered(s))
        assertEquals(SessionOutcome.COMPLETE, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        // The same text without a single provider citation: nothing is established, so nothing is "your call".
        val fx = Research2Fixtures.fragranceGather()
        val uncited = merged(Research2Fixtures.FRAGRANCE_REQUEST, fx.copy(segments = fx.segments.map { it.copy(citations = emptyList()) }))
        assertEquals(RequirementStatus.NEEDS_CHECK, uncited.coverageOf("r-c2").status)
        assertEquals(RequirementStatus.NEEDS_CHECK, uncited.coverageOf("r-i0").status)
        assertTrue(uncited.body.candidates.all { it.price?.status != PriceStatus.CONFIRMED })
        assertEquals("every price unconfirmed → the budget cannot be confirmed", RequirementStatus.NEEDS_CHECK, uncited.coverageOf("r-c0").status)
        assertFalse(SessionAssembler.covered(uncited))
        assertEquals(SessionOutcome.PARTIAL, SessionAssembler.outcome(uncited, SessionAssembler.Ending.NORMAL))
    }

    @Test fun modelFalselyMarkingAnUnsupportedRequirementAnsweredIsDowngraded() {
        val lie = done(RawSegment("## REQ r-i0 · status=answered\nFound it.\n## REQ r-i1 · status=answered\nok\n## REQ r-i2 · status=resolved_conflict\nok\n## REQ r-i3 · status=your_call\nok\n## REQ r-c0 · status=answered\nok\n## REQ r-o0 · status=answered\nok\n", emptyList()))
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, lie)
        for (id in listOf("r-i0", "r-i1", "r-i2", "r-i3", "r-c0")) assertEquals(id, RequirementStatus.NEEDS_CHECK, s.coverageOf(id).status)
        assertTrue(s.coverageOf("r-i0").summary.startsWith("Reported as answered, but no cited page backs it."))
        assertTrue(s.coverageOf("r-i3").summary.startsWith("Reported as your call"))
        assertEquals(RequirementStatus.NOT_FOUND, s.coverageOf("r-o0").status)
        assertFalse(SessionAssembler.covered(s))
        assertEquals(SessionOutcome.INCOMPLETE_EVIDENCE, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        // A pick block that found nothing can never be Answered.
        val none = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, done(RawSegment("## PICK r-i0 · status=answered\nPick: none found\nWhy: nothing\n", emptyList())))
        assertEquals(RequirementStatus.NOT_FOUND, none.coverageOf("r-i0").status)
    }

    @Test fun outcomeAndOnScreenCoverageAlwaysAgree() {
        val sessions = listOf(
            merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather()),
            merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather(), glucosamineElsewhere("partly")),
            merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather(), glucosamineElsewhere("answered")),
            merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather()),
            merged(Research2Fixtures.FRAGRANCE_REQUEST, fragranceWith("210 USD · 50 ml · Brand House")),
            merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps()),
        )
        for (s in sessions) {
            val complete = SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL) == SessionOutcome.COMPLETE
            val gaps = SessionAssembler.gaps(s)
            assertEquals("Complete ⇔ no requirement row is open", complete, gaps.isEmpty())
            assertEquals(complete, SessionAssembler.covered(s))
            assertEquals("every open row is a gap the banner names", s.requirements.filter { s.coverageOf(it.id).status.open }.map { it.id }, gaps.map { it.id })
            assertTrue(s.coverage.size == s.requirements.size)
        }
        assertEquals(listOf(SessionOutcome.INCOMPLETE_EVIDENCE, SessionOutcome.PARTIAL, SessionOutcome.COMPLETE, SessionOutcome.PARTIAL, SessionOutcome.PARTIAL, SessionOutcome.COMPLETE), sessions.map { SessionAssembler.outcome(it, SessionAssembler.Ending.NORMAL) })
    }

    @Test fun extractorMarksPreferencesSoftAndBoundsStrict() {
        val supp = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST).requirements
        assertTrue(supp.first { it.label.startsWith("Brand preference") }.soft)
        assertEquals("the brand list stops at the end of its sentence", "Brand preference: Thorne and Pure Encapsulations", supp.first { it.label.startsWith("Brand preference") }.label)
        val frag = RequirementExtractor.extract(Research2Fixtures.FRAGRANCE_REQUEST).requirements
        assertFalse(frag.first { it.label.startsWith("Budget") }.soft)
        assertEquals("Budget: up to $150", frag.first { it.label.startsWith("Budget") }.label)
        val only = RequirementExtractor.extract("Only Thorne. Need a multivitamin and magnesium.").requirements
        assertTrue(only.none { it.label.startsWith("Brand") } || !only.first { it.label.startsWith("Brand") }.soft)
    }
}
