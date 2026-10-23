package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** M3A.2 proposals become canonical research proposal sets: idempotent id, src from refs, verify items, legacy applied history kept. */
class ResearchProposalBridgeTest {
    private val now = Instant.parse("2026-10-23T09:00:00Z")

    private fun session(applied: List<AppliedProposal> = emptyList()): ResearchSession {
        val reqs = listOf(Requirement("r-i0", RequirementType.ITEM, "multivitamin"), Requirement("r-i1", RequirementType.ITEM, "vitamin D"))
        val content = SessionContent(
            title = "Thorne & Pure picks", request = "Pick supplements", template = DeliverableTemplate.PICK_PER_ITEM, requirements = reqs,
            coverage = listOf(Coverage("r-i0", RequirementStatus.ANSWERED, "Thorne Basic Nutrients 2/Day, third-party tested", refs = listOf(1)), Coverage("r-i1", RequirementStatus.NEEDS_CHECK, "Dose unclear", refs = listOf(2))),
            sources = listOf(SourceRef2(1, "https://thorne.example/basic", "Basic Nutrients 2/Day | Thorne", "thorne.example", true, firstRunId = "run1"), SourceRef2(2, "https://forum.example/d", "Forum: vitamin D dosing", "forum.example", false, firstRunId = "run1")),
            proposals = listOf(
                Proposal("p-step", ProposalKind.STEP, "Buy multivitamin: Thorne Basic Nutrients 2/Day", "Third-party tested · $32 at Thorne", "r-i0", listOf(1), 1_000),
                Proposal("p-check", ProposalKind.CHECK, "vitamin D dose with your GP", "Forum posts only", "r-i1", listOf(2), 1_000, selectedByDefault = false),
                Proposal("p-note", ProposalKind.NOTE, "Budget", "About $60 a month for both", null, emptyList(), 1_000),
                Proposal("p-research", ProposalKind.RESEARCH, "glucosamine further", "Nothing found yet.", null, emptyList(), 1_000, selectedByDefault = false, generated = true),
            ),
            runs = listOf(RunRecord("run1", RunKind.INITIAL, now.toEpochMilli(), ResearchMode.THOROUGH, "claude-sonnet-5-5", 10, null, emptyList(), RunUsage(), SessionOutcome.INCOMPLETE_EVIDENCE)),
            outcome = SessionOutcome.INCOMPLETE_EVIDENCE,
        )
        return ResearchSession("sess1", "t1", now, now, content, applied)
    }

    private val snapshot = TargetSnapshot.of(Task("t1", "Supplements", createdAt = now, updatedAt = now), emptyList(), null, 0)

    @Test fun mapsKindsOriginsRecommendationsAndSrc() {
        val set = ResearchProposalBridge.build(session(), snapshot, 2_000)
        assertEquals("rps-sess1-run1", set.id)
        assertEquals(ProposalSource.RESEARCH, set.source)
        assertEquals("sess1", set.investigationId)
        assertEquals("run1", set.turnId)
        assertEquals("claude-sonnet-5-5", set.model)
        assertEquals(SetStatus.OPEN, set.status)
        assertTrue("nothing pre-selected: every item pending", set.items.all { it.status == ItemStatus.PENDING })

        val step = set.item("p-step")!!
        assertEquals(ItemKind.ADD_STEP, step.kind)
        assertEquals(ContentOrigin.RESEARCH, (step.value as ItemValue.Step).origin)
        assertTrue("sourced step is recommended", step.recommended)
        assertFalse(step.material)
        assertEquals("established", step.src!!.status)
        assertEquals(listOf("https://thorne.example/basic"), step.src!!.findings.single().keys)
        assertEquals("s1", step.src!!.findings.single().ref)
        assertEquals("Third-party tested · $32 at Thorne", step.why)

        val check = set.item("p-check")!!
        assertEquals("Check: vitamin D dose with your GP", (check.value as ItemValue.Step).text)
        assertEquals(ContentOrigin.UNVERIFIED, (check.value as ItemValue.Step).origin)
        assertTrue((check.value as ItemValue.Step).verify)
        assertFalse("verification items are never recommended", check.recommended)
        assertEquals("indicative", check.src!!.status)

        val note = set.item("p-note")!!
        assertEquals(ItemKind.APPEND_NOTE, note.kind)
        assertEquals("Budget — About $60 a month for both", (note.value as ItemValue.Text).text)
        assertFalse(note.recommended)
        assertNull("no refs, no requirement → no src", note.src)

        val research = set.item("p-research")!!
        assertEquals("Research: glucosamine further", (research.value as ItemValue.Step).text)
        assertTrue((research.value as ItemValue.Step).verify)
    }

    @Test fun legacyAppliedMovesStayVisibleAsAlreadyApplied() {
        val set = ResearchProposalBridge.build(session(applied = listOf(AppliedProposal("p-step", 1_500, "Buy multivitamin: Thorne Basic Nutrients 2/Day", "s9"))), snapshot, 2_000)
        assertEquals(ItemStatus.APPLIED, set.item("p-step")!!.status)
        assertEquals("Applied earlier", set.item("p-step")!!.statusReason)
        assertEquals(3, set.pending.size)
        assertEquals(SetStatus.OPEN, set.status)
    }

    @Test fun sameSessionAndRunAlwaysMapToTheSameSetId() {
        assertEquals(ResearchProposalBridge.setId("sess1", "run1"), ResearchProposalBridge.build(session(), snapshot, 5).id)
        assertTrue(ResearchProposalBridge.setId("sess1", "run2") != ResearchProposalBridge.setId("sess1", "run1"))
    }
}
