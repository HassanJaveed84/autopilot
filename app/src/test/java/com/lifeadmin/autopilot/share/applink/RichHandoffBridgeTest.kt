package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalJson
import com.lifeadmin.autopilot.domain.proposal.ProposalReconciler
import com.lifeadmin.autopilot.domain.proposal.StepSnapshot
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rich steps map to one review item per step: a new step with its details, or only the new details
 * for a step that is already there. A name that matches several steps is never guessed.
 */
class RichHandoffBridgeTest {
    private val photoLinks = listOf(StepLink("https://www.travel.state.gov/content/photos.html/"))
    private val snapshot = TargetSnapshot(
        rev = 1, title = "Renew passport", status = "ACTIONABLE",
        steps = listOf(
            StepSnapshot("u1", 0, "Get a passport photo", isDone = false, description = "CVS on Main St does them.", links = photoLinks),
            StepSnapshot("u2", 1, "Mail the application", isDone = true),
            StepSnapshot("u3", 2, "Call the office", isDone = false),
            StepSnapshot("u4", 3, "call the office!", isDone = true),
        ),
        takenAtMs = 0,
    )

    private fun handoff(vararg steps: Pair<String, HandoffStepDetails?>) = LinkHandoff(
        "Renew passport", emptyList(), steps.map { it.first }, emptyList(), "task-1", version = 2,
        stepDetails = steps.withIndex().mapNotNull { (i, p) -> p.second?.let { i to it } }.toMap(),
    )

    private fun build(h: LinkHandoff) = HandoffProposalBridge.build(h, "task-1", snapshot, nowMs = 1)

    @Test fun aNewRichStepIsOneItemWithItsDescriptionAndLinks() {
        val d = HandoffStepDetails(listOf("Expedited costs extra.", "Pick it if the trip is close."), listOf(StepLink("https://travel.state.gov/fees.html", "Fees"), StepLink("https://travel.state.gov/times.html")))
        val item = build(handoff("Choose routine or expedited service" to d)).items.single()
        assertEquals(ItemKind.ADD_STEP, item.kind)
        val v = item.value as ItemValue.Step
        assertEquals("Choose routine or expedited service", v.text)
        assertEquals("Expedited costs extra.\nPick it if the trip is close.", v.description)
        assertEquals(d.links, v.links)
        assertTrue(item.recommended)
        assertFalse(item.material)
    }

    @Test fun detailsForTheOneMatchingStepCarryOnlyWhatIsNewAndItsStableId() {
        val d = HandoffStepDetails(
            listOf("2 x 2 in, white background.", "CVS on Main St. does them"),
            listOf(StepLink("https://travel.state.gov/content/photos.html?utm_source=x"), StepLink("https://tools.example.com/find", "Find a location")),
        )
        val item = build(handoff("get a passport photo!" to d)).items.single()
        assertEquals(ItemKind.ADD_STEP_DETAILS, item.kind)
        val v = item.value as ItemValue.StepDetails
        assertEquals("u1", v.stepId)
        assertEquals("2 x 2 in, white background.", v.description)
        assertEquals(listOf(StepLink("https://tools.example.com/find", "Find a location")), v.links)
        assertFalse("adding details never needs its own OK", ProposalReconciler.isMaterial(item, snapshot))
    }

    @Test fun nothingNewOrAPlainMentionProposesNothing() {
        assertTrue(build(handoff("Get a passport photo" to HandoffStepDetails(listOf("cvs on main st does them"), photoLinks))).items.isEmpty())
        assertTrue(build(handoff("Get a passport photo" to null)).items.isEmpty())
        // Even an ambiguous name needs no choice when there is nothing to add.
        assertTrue(build(handoff("Call the office" to null)).items.isEmpty())
    }

    @Test fun anAmbiguousNameWaitsForTheUserToChooseTheStep() {
        val d = HandoffStepDetails(listOf("Ask for the passport desk."), listOf(StepLink("https://example.gov/contact")))
        val item = build(handoff("Call the office" to d)).items.single()
        val v = item.value as ItemValue.StepDetails
        assertNull(v.stepId)
        assertEquals(listOf("u3", "u4"), v.choices)
        assertEquals("Call the office", v.stepTitle)
        val check = ProposalReconciler.reconcile(build(handoff("Call the office" to d)), snapshot).single()
        assertEquals(ProposalReconciler.CHOOSE_STEP, check.reason)
        assertTrue(check.blocksApply)
        assertTrue("never bulk-selected", ProposalReconciler.recommendedSelection(build(handoff("Call the office" to d)), listOf(check)).isEmpty())
    }

    @Test fun aNameRepeatedInTheLinkBecomesOneUnit() {
        val items = build(
            handoff(
                "Book photo slot" to HandoffStepDetails(listOf("Morning is quieter."), listOf(StepLink("https://tools.example.com/find"))),
                "book photo slot" to HandoffStepDetails(listOf("morning is quieter", "Bring ID."), listOf(StepLink("https://tools.example.com/find#map"), StepLink("https://example.com/b"))),
            ),
        ).items
        val v = items.single().value as ItemValue.Step
        assertEquals("Morning is quieter.\nBring ID.", v.description)
        assertEquals(listOf("https://tools.example.com/find", "https://example.com/b"), v.links.map { it.url })
    }

    @Test fun theSetIdIsStableForPlainStepsAndChangesWithDetails() {
        val v1 = LinkHandoff("Renew passport", emptyList(), listOf("Book photo slot"), emptyList(), "task-1")
        val plainV2 = v1.copy(version = 2)
        assertEquals(HandoffProposalBridge.setId("task-1", v1), HandoffProposalBridge.setId("task-1", plainV2))
        val rich = plainV2.copy(stepDetails = mapOf(0 to HandoffStepDetails(listOf("Morning"))))
        assertNotEquals(HandoffProposalBridge.setId("task-1", v1), HandoffProposalBridge.setId("task-1", rich))
        assertEquals(HandoffProposalBridge.setId("task-1", rich), HandoffProposalBridge.setId("task-1", rich.copy()))
    }

    @Test fun richItemsSurviveStorageAndOldStoredItemsStillDecode() {
        val d = HandoffStepDetails(listOf("Ask for the passport desk."), listOf(StepLink("https://example.gov/contact", "Contact")))
        val set = build(handoff("Call the office" to d, "New step" to d))
        assertEquals(set, ProposalJson.decodeSet(ProposalJson.encodeSet(set)))
        // A step value and a snapshot stored by 0.2.0 (no details) decode with empty details.
        val old = """{"type":"step","text":"Old step","origin":"USER","verify":false,"supersedes":false,"afterStepId":null}"""
        val step = ProposalJson.json.decodeFromString(ItemValue.serializer(), old) as ItemValue.Step
        assertNull(step.description); assertTrue(step.links.isEmpty())
        val oldSnap = """{"id":"s","sortOrder":0,"title":"T","isDone":false,"origin":"USER","provenanceId":null}"""
        val snap = ProposalJson.json.decodeFromString(StepSnapshot.serializer(), oldSnap)
        assertNull(snap.description); assertTrue(snap.links.isEmpty())
    }
}
