package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.testing.Research2Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAssemblerTest {
    private val now = 1_700_000_000_000L

    private fun session(request: String): SessionContent {
        val r = RequirementExtractor.extract(request)
        return SessionContent("t", request, r.template, r.requirements, r.requirements.map { Coverage(it.id, RequirementStatus.PENDING) }, outcome = SessionOutcome.INTERRUPTED)
    }

    private fun merged(request: String, vararg calls: CallResult.Done): SessionContent {
        var s = session(request)
        val registry = SourceRegistry2()
        for (c in calls) s = SessionAssembler.merge(s, ContractParser.parse(c.segments, c.searches, registry, s.requirements, now, s.body.candidates), registry.sources())
        return s
    }

    @Test fun supplementsBenchmarkProducesFourProposalsAndHonestOutcome() {
        val s = SessionAssembler.settlePending(merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather()))
        assertFalse(SessionAssembler.covered(s))
        assertEquals(SessionOutcome.INCOMPLETE_EVIDENCE, SessionAssembler.outcome(s, SessionAssembler.Ending.NORMAL))
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, SessionAssembler.outcome(s, SessionAssembler.Ending.BUDGET_SEARCHES))
        assertEquals("3 of 4 ready · 1 not found", SessionAssembler.coverageLine(s))
        val proposals = ProposalBuilder.complete(s, now)
        assertEquals(4, proposals.size)
        assertEquals(listOf("r-i0", "r-i1", "r-i2", "r-i3"), proposals.map { it.requirementId })
        val glucosamine = proposals.last()
        assertEquals(ProposalKind.RESEARCH, glucosamine.kind)
        assertTrue(glucosamine.generated)
        assertFalse(glucosamine.selectedByDefault)
        assertTrue(proposals.take(3).all { !it.generated && it.kind == ProposalKind.STEP })
        // Nothing resembling a dose was chosen for the person.
        assertTrue(s.body.picks.mapNotNull { it.forYou }.all { it.startsWith("Ask your") })
        assertEquals(listOf("r-i0", "r-i1", "r-i2"), SessionAssembler.gaps(s).let { gaps -> s.requirements.filter { r -> r.type == RequirementType.ITEM && gaps.none { it.id == r.id } }.map { it.id } })
        assertEquals(listOf("r-i3"), SessionAssembler.gaps(s).map { it.id })
    }

    @Test fun fragranceBenchmarkMergesSecondPassAndBecomesComplete() {
        val s1 = merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather())
        assertEquals(listOf("c3"), SessionAssembler.priceGaps(s1).map { it.id })
        assertTrue(SessionAssembler.covered(s1))
        assertEquals(listOf("price", "lasts", "gift"), s1.body.compareKeys)
        val s2 = merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        assertEquals(3, s2.body.candidates.size)
        val c3 = s2.body.candidates.first { it.id == "c3" }
        assertEquals(PriceStatus.CONFIRMED, c3.price!!.status)
        assertEquals("earlier attributes survive the merge", ProvenanceTag.JUDGMENT, c3.attrs.first { it.key == "gift" }.tag)
        assertEquals(emptyList<Candidate>(), SessionAssembler.priceGaps(s2))
        assertEquals(SessionOutcome.COMPLETE, SessionAssembler.outcome(SessionAssembler.settlePending(s2), SessionAssembler.Ending.NORMAL))
        assertEquals(5, s2.sources.size)
        assertEquals(4, s2.queries.size)
        assertEquals(1, s2.body.excluded.size)
        assertEquals(RequirementStatus.YOUR_CALL, s2.coverageOf("r-c2").status)
        val proposals = ProposalBuilder.complete(s2, now)
        assertEquals("model proposals kept; a DECIDE already exists so none is generated", 2, proposals.size)
        val buy = proposals.first { it.kind == ProposalKind.STEP }
        assertTrue("a shortlist step inherits the named candidate's evidence", buy.refs.isNotEmpty())
        assertTrue(buy.selectedByDefault)
        assertTrue(s2.body.candidates.map { it.style }.distinct().size >= 3)
    }

    @Test fun endingRulesNeverCallABudgetStopComplete() {
        val s = SessionAssembler.settlePending(merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather()))
        assertEquals(SessionOutcome.STOPPED, SessionAssembler.outcome(s, SessionAssembler.Ending.STOPPED))
        assertEquals(SessionOutcome.PARTIAL, SessionAssembler.outcome(s, SessionAssembler.Ending.FAILED_AFTER_PARTIAL))
        assertEquals(SessionOutcome.PARTIAL, SessionAssembler.outcome(s, SessionAssembler.Ending.TRUNCATED))
        assertEquals(SessionOutcome.FAILED, SessionAssembler.outcome(s, SessionAssembler.Ending.FAILED))
        assertEquals(SessionOutcome.INCOMPLETE_BUDGET, SessionAssembler.outcome(s, SessionAssembler.Ending.BUDGET_SPEND))
        val complete = merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather())
        assertEquals("covered + budget stop is still Complete", SessionOutcome.COMPLETE, SessionAssembler.outcome(complete, SessionAssembler.Ending.BUDGET_SEARCHES))
    }

    @Test fun pendingRequirementsAfterARunCountAsNotFound() {
        val s = session(Research2Fixtures.SUPPLEMENTS_REQUEST)
        val settled = SessionAssembler.settlePending(s)
        assertTrue(settled.coverage.all { it.status == RequirementStatus.NOT_FOUND })
        assertEquals("0 of 4 ready · 4 not found", SessionAssembler.coverageLine(settled))
    }

    @Test fun howToStepsBecomeStepProposalsWhenTheModelGaveNone() {
        val r = RequirementExtractor.extract("How do I renew a passport?")
        var s = SessionContent("t", "How do I renew a passport?", r.template, r.requirements, outcome = SessionOutcome.INTERRUPTED)
        val registry = SourceRegistry2()
        val c = com.lifeadmin.autopilot.ai.research.RawCitation("https://www.gov.uk/renew-adult-passport", "Renew", "Apply online")
        val out = ContractParser.parse(
            listOf(
                com.lifeadmin.autopilot.ai.research.RawSegment("## REQ r-i0 · status=answered\nok\n## REQ r-o0 · status=answered\nok\n## STEP\n", emptyList()),
                com.lifeadmin.autopilot.ai.research.RawSegment("Apply online on the government site.", listOf(c)),
                com.lifeadmin.autopilot.ai.research.RawSegment("\n## STEP\nTake a new photo (optional)\n## STEP\nPay the fee\n", emptyList()),
            ),
            emptyList(), registry, s.requirements, now,
        )
        s = SessionAssembler.merge(s, out, registry.sources())
        assertEquals(3, s.body.steps.size)
        assertTrue(s.body.steps[1].optional)
        assertEquals(ProvenanceTag.FACT, s.body.steps[0].tag)
        assertTrue(s.sources[0].official)
        val proposals = ProposalBuilder.complete(s, now)
        assertEquals(listOf("Apply online on the government site", "Pay the fee"), proposals.map { it.title })
        assertTrue(proposals[0].selectedByDefault)
        assertFalse(proposals[1].selectedByDefault)
        assertTrue("a how-to never gets a per-item 'research further' proposal", proposals.none { it.kind == ProposalKind.RESEARCH })
    }
}
