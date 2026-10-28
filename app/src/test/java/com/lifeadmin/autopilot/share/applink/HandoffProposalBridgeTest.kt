package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalJson
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.StepSnapshot
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A validated handoff maps to the canonical proposal set: pure, deterministic, deduplicated, nothing material. */
class HandoffProposalBridgeTest {
    private val snapshot = TargetSnapshot(
        rev = 3, title = "Choose a coffee grinder", notes = "Budget is \$1,000\nAsk Sam", status = "ACTIONABLE",
        steps = listOf(StepSnapshot("a", 0, "Set a budget", isDone = true), StepSnapshot("b", 1, "Read reviews", isDone = false)),
        takenAtMs = 0,
    )
    private val handoff = LinkHandoff(
        task = "Coffee grinder",
        decisions = listOf("Choose Model A", "Decided: buy in December"),
        steps = listOf("Compare retailer prices", "set a BUDGET!", "Check warranty coverage", "Compare retailer prices.", "Read reviews"),
        notes = listOf("Budget is \$1,000", "Model B is louder"),
        ref = "task-1",
    )

    private fun build(h: LinkHandoff = handoff) = HandoffProposalBridge.build(h, "task-1", snapshot, nowMs = 42)

    @Test fun stepsBecomeAdditionsAndDecisionsAndNotesBecomeNoteLines() {
        val set = build()
        assertEquals(ProposalSource.HANDOFF, set.source)
        assertEquals(TargetKind.TASK, set.targetKind)
        assertEquals("task-1", set.targetId)
        assertEquals("From ChatGPT", set.title)
        assertEquals("", set.model)
        assertEquals(snapshot, set.baseOriginal)
        val steps = set.items.filter { it.kind == ItemKind.ADD_STEP }.map { it.value as ItemValue.Step }
        assertEquals(listOf("Compare retailer prices", "Check warranty coverage"), steps.map { it.text })
        assertTrue(steps.all { it.origin == ContentOrigin.USER && !it.verify && it.afterStepId == null })
        val notes = set.items.filter { it.kind == ItemKind.APPEND_NOTE }.map { (it.value as ItemValue.Text).text }
        assertEquals(listOf("Decided: Choose Model A", "Decided: buy in December", "Model B is louder"), notes)
    }

    @Test fun duplicatesOfExistingDoneOrOpenStepsAndExistingNoteLinesAreLeftOut() {
        val set = build()
        val texts = set.items.map { (it.value as? ItemValue.Step)?.text ?: (it.value as ItemValue.Text).text }
        // "set a BUDGET!" (a done step), "Read reviews" (open), the repeated "Compare retailer prices." and the existing budget note.
        assertFalse(texts.contains("set a BUDGET!"))
        assertFalse(texts.contains("Budget is \$1,000"))
        assertFalse(texts.contains("Read reviews"))
        assertEquals(1, texts.count { it.startsWith("Compare retailer prices") })
    }

    @Test fun nothingIsMaterialPreSelectedOrARenameOrAStatus() {
        val set = build()
        assertTrue(set.items.all { it.status == ItemStatus.PENDING && it.recommended && !it.material })
        assertTrue(set.items.all { it.kind == ItemKind.ADD_STEP || it.kind == ItemKind.APPEND_NOTE })
        assertTrue(set.items.none { it.kind == ItemKind.SET_TITLE || it.kind == ItemKind.EDIT_STEP || it.kind == ItemKind.REMOVE_STEP || it.kind == ItemKind.SET_STATUS })
    }

    @Test fun theSetIdDependsOnTheTaskAndTheChangesOnly() {
        val id = HandoffProposalBridge.setId("task-1", handoff)
        assertEquals(id, build().id)
        assertEquals(id, HandoffProposalBridge.setId("task-1", handoff.copy(task = "Another title", ref = null)))
        assertNotEquals(id, HandoffProposalBridge.setId("task-2", handoff))
        assertNotEquals(id, HandoffProposalBridge.setId("task-1", handoff.copy(steps = handoff.steps + "One more")))
        // A step and a note with the same words are different changes.
        assertNotEquals(HandoffProposalBridge.setId("t", LinkHandoff("T", emptyList(), listOf("X"), emptyList())), HandoffProposalBridge.setId("t", LinkHandoff("T", emptyList(), emptyList(), listOf("X"))))
        assertTrue(Regex("hps-[0-9a-f]{24}").matches(id))
        assertEquals(build().items.size, build().items.map { it.id }.toSet().size)
    }

    @Test fun aHandoffWithNothingNewBuildsAnEmptySet() {
        val set = build(LinkHandoff("T", emptyList(), listOf("Read reviews"), listOf("Ask Sam")))
        assertTrue(set.items.isEmpty())
    }

    @Test fun theSetSurvivesSerialisationWithItsSource() {
        val set = build()
        assertEquals(set, ProposalJson.decodeSet(ProposalJson.encodeSet(set)))
        assertTrue(ProposalJson.encodeSet(set).contains("\"source\":\"HANDOFF\""))
    }
}
