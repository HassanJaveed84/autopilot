package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.domain.proposal.ApplyChoice
import com.lifeadmin.autopilot.domain.proposal.ApplyResult
import com.lifeadmin.autopilot.domain.proposal.CheckOutcome
import com.lifeadmin.autopilot.domain.proposal.ConflictResolution
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.ProposalItem
import com.lifeadmin.autopilot.domain.proposal.ProposalSet
import com.lifeadmin.autopilot.domain.proposal.ProposalSource
import com.lifeadmin.autopilot.domain.proposal.TargetKind
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.share.applink.HandoffImporter
import com.lifeadmin.autopilot.share.applink.HandoffPrepared
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.ImportLinkResult
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Rich Steps against Room: editing keeps everything else about a step, ChatGPT's details only ever
 * add, an ambiguous match is never guessed, repeats are idempotent, and undo restores every field of
 * every step (including the details of steps the apply didn't touch).
 */
@RunWith(AndroidJUnit4::class)
class RichStepsTest {
    private lateinit var db: AppDatabase
    private lateinit var tasks: TaskRepository
    private lateinit var sets: ProposalSetRepository
    private lateinit var applier: ProposalApplier
    private val clock = FakeClock(Instant.parse("2026-10-28T15:00:00Z"))
    private val ids = SequentialIds("id")
    private val host = "hassanjaveed84.github.io"

    private val photo = StepLink("https://travel.state.gov/content/photos.html", "Photo rules")
    private val usps = StepLink("https://tools.usps.com/find-location.htm", "Find a location")

    @Before fun setUp() {
        db = inMemoryDatabase()
        tasks = TaskRepository(db, clock, ids, { CHICAGO }, ScheduleCoordinator.Direct)
        sets = ProposalSetRepository(db)
        applier = ProposalApplier(db, tasks, sets, clock, ids)
    }

    @After fun tearDown() = db.close()

    private lateinit var taskId: String
    private lateinit var photoStep: String
    private lateinit var mailStep: String
    private lateinit var callA: String
    private lateinit var callB: String

    private suspend fun seed() {
        taskId = tasks.createTask(NewTask("Renew passport", status = TaskStatus.ACTIONABLE))
        photoStep = tasks.addStep(taskId, "Get a passport photo")
        tasks.updateStep(photoStep, "Get a passport photo", "CVS on Main St does them.", listOf(photo))
        mailStep = tasks.addStep(taskId, "Mail the application")
        tasks.updateStep(mailStep, "Mail the application", "Use tracked mail.", listOf(StepLink("https://example.gov/where-to-mail", "Where to mail")))
        tasks.setStepDone(mailStep, true)
        callA = tasks.addStep(taskId, "Call the office")
        callB = tasks.addStep(taskId, "call the office!")
    }

    private suspend fun steps() = tasks.getSteps(taskId).associateBy { it.id }
    private suspend fun step(id: String): TaskStep = steps().getValue(id)

    private suspend fun set(vararg items: ProposalItem): ProposalSet {
        val snap = applier.snapshot(taskId)!!
        val s = ProposalSet(
            id = "set-" + ids.newId(), source = ProposalSource.HANDOFF, targetKind = TargetKind.TASK, targetId = taskId, madeAtMs = 0,
            baseOriginal = snap, base = snap, items = items.toList(), title = "From ChatGPT",
        )
        sets.save(s)
        return s
    }

    private fun details(id: String?, description: String?, vararg links: StepLink, choices: List<String> = emptyList()) =
        ProposalItem("d-${ids.newId()}", ItemKind.ADD_STEP_DETAILS, value = ItemValue.StepDetails(id, choices, "Step", description, links.toList()), recommended = true)

    @Test fun theStepEditorSavesDetailsAndKeepsDoneStateOrderAndOrigin() = runTest {
        seed()
        val before = step(mailStep)
        tasks.updateStep(mailStep, "Mail the application by Nov 15", "Use tracked mail.\nKeep the receipt.", listOf(StepLink("www.usps.com/track?utm_source=x&id=1", " Track ")))
        val after = step(mailStep)
        assertEquals("Mail the application by Nov 15", after.title)
        assertEquals("Use tracked mail.\nKeep the receipt.", after.description)
        assertEquals(listOf(StepLink("https://www.usps.com/track?id=1", "Track")), after.links)
        assertEquals(before.isDone, after.isDone)
        assertEquals(before.completedAt, after.completedAt)
        assertEquals(before.sortOrder, after.sortOrder)
        assertEquals(before.origin, after.origin)
        // Renaming, ticking and moving keep the details.
        tasks.renameStep(mailStep, "Mail it")
        tasks.setStepDone(mailStep, false)
        tasks.moveStep(mailStep, -1)
        assertEquals(after.description, step(mailStep).description)
        assertEquals(after.links, step(mailStep).links)
        listOf<suspend () -> Unit>(
            { tasks.updateStep(mailStep, "T", null, listOf(StepLink("javascript:alert(1)"))) },
            { tasks.updateStep(mailStep, "T", "x".repeat(2_001), emptyList()) },
            { tasks.updateStep(mailStep, " ", null, emptyList()) },
        ).forEach { attempt -> try { attempt(); fail("expected a validation error") } catch (_: ValidationException) {} }
        assertEquals("Mail it", step(mailStep).title)
    }

    @Test fun addingDetailsKeepsTheUsersWordsMainLinkAndDoneStateAndOnlyAddsWhatsNew() = runTest {
        seed()
        val s = set(
            details(photoStep, "2 x 2 in, white background.\ncvs on main st does them", photo.copy(url = "https://www.travel.state.gov/content/photos.html/?utm_source=chatgpt.com"), usps),
            details(mailStep, "Allow 6 weeks.", usps),
        )
        val checks = applier.reconcile(s).associateBy { it.itemId }
        assertEquals(CheckOutcome.OK, checks.getValue(s.items[0].id).outcome)
        assertEquals("a ticked step needs an explicit choice", CheckOutcome.CONFLICT, checks.getValue(s.items[1].id).outcome)
        val r = applier.apply(s.id, listOf(ApplyChoice(s.items[0].id), ApplyChoice(s.items[1].id, resolution = ConflictResolution.APPLY_ANYWAY))) as ApplyResult.Applied
        assertEquals(2, r.appliedItemIds.size)
        val p = step(photoStep)
        assertEquals("Get a passport photo", p.title)
        assertEquals(ContentOrigin.USER, p.origin)
        assertEquals("CVS on Main St does them.\n\n2 x 2 in, white background.", p.description)
        assertEquals(listOf(photo, usps), p.links)
        val m = step(mailStep)
        assertTrue("enriching a done step leaves it done", m.isDone)
        assertEquals("Use tracked mail.\n\nAllow 6 weeks.", m.description)
        assertEquals(2, m.links.size)
    }

    @Test fun repeatedImportsAndRepeatedApplicationAreIdempotent() = runTest {
        seed()
        val parser = ImportLinkParser(host)
        val url = "https://$host/autopilot/import#ap=v2&r=$taskId&t=Renew%20passport" +
            "&s=Get%20a%20passport%20photo&sd=2%20x%202%20in%2C%20white%20background.&sl=Find%20a%20location%20https%3A%2F%2Ftools.usps.com%2Ffind-location.htm" +
            "&s=Choose%20routine%20or%20expedited&sd=Expedited%20costs%20extra.&sl=Fees%20https%3A%2F%2Ftravel.state.gov%2Ffees.html&end=1"
        val h = (parser.parse(url) as ImportLinkResult.Valid).handoff
        val importer = HandoffImporter(tasks, sets, applier, clock)
        val first = importer.prepare(h, taskId) as HandoffPrepared.Ready
        assertEquals(HandoffPrepared.Ready(taskId, first.setId, existed = true), importer.prepare(h, taskId))
        val set = sets.get(first.setId)!!
        assertEquals(listOf(ItemKind.ADD_STEP_DETAILS, ItemKind.ADD_STEP), set.items.map { it.kind })
        applier.apply(set.id, set.items.map { ApplyChoice(it.id) }) as ApplyResult.Applied
        val afterFirst = steps()
        assertTrue("applied items can't be applied again", applier.apply(set.id, set.items.map { ApplyChoice(it.id) }) is ApplyResult.Rejected)
        assertEquals(afterFirst, steps())
        // The same details in another set (a second conversation) add nothing.
        val again = set(details(photoStep, "2 x 2 in, white background.", usps))
        assertEquals(CheckOutcome.NOTE, applier.reconcile(again).single().outcome)
        applier.apply(again.id, listOf(ApplyChoice(again.items.single().id))) as ApplyResult.Applied
        assertEquals(afterFirst.getValue(photoStep).copy(), step(photoStep))
        assertEquals(1, step(photoStep).links.count { it.url == usps.url })
        // Another link with the same details for the same step proposes only what is still new: nothing.
        val sameDetails = (parser.parse(url.substringBefore("&s=Choose") + "&end=1") as ImportLinkResult.Valid).handoff
        assertEquals(HandoffPrepared.NothingNew, importer.prepare(sameDetails, taskId))
    }

    @Test fun anAmbiguousMatchIsNeverGuessedAndAppliesOnlyToTheChosenStep() = runTest {
        seed()
        val item = details(null, "Ask for the passport desk.", usps, choices = listOf(callA, callB))
        val s = set(item)
        assertEquals(CheckOutcome.CONFLICT, applier.reconcile(s).single().outcome)
        assertTrue(applier.apply(s.id, listOf(ApplyChoice(item.id))) is ApplyResult.NeedsReview)
        // "Apply anyway" without a step is refused, and writes nothing.
        assertTrue(applier.apply(s.id, listOf(ApplyChoice(item.id, resolution = ConflictResolution.APPLY_ANYWAY))) is ApplyResult.Rejected)
        assertNull(step(callA).description); assertNull(step(callB).description)
        val chosen = (item.value as ItemValue.StepDetails).copy(stepId = callB)
        applier.apply(s.id, listOf(ApplyChoice(item.id, chosen))) as ApplyResult.Applied
        assertNull(step(callA).description)
        assertEquals("Ask for the passport desk.", step(callB).description)
        assertEquals(listOf(usps), step(callB).links)
    }

    @Test fun undoRestoresEveryFieldAndLeavesUnrelatedDetailsAlone() = runTest {
        seed()
        val before = steps()
        val newStep = ProposalItem(
            "n1", ItemKind.ADD_STEP,
            value = ItemValue.Step("Choose routine or expedited", ContentOrigin.USER, description = "Expedited costs extra.", links = listOf(StepLink("https://travel.state.gov/fees.html", "Fees"))),
            recommended = true,
        )
        val s = set(newStep, details(photoStep, "2 x 2 in.", usps))
        applier.apply(s.id, s.items.map { ApplyChoice(it.id) }) as ApplyResult.Applied
        val added = tasks.getSteps(taskId).single { it.title == "Choose routine or expedited" }
        assertEquals("Expedited costs extra.", added.description)
        assertEquals(listOf(StepLink("https://travel.state.gov/fees.html", "Fees")), added.links)
        assertTrue(applier.undo(s.id))
        assertEquals("every step is exactly as before, details included", before, steps())

        // Undo of a plain rename keeps that step's details and every other step's.
        val rename = ProposalItem("e1", ItemKind.EDIT_STEP, anchorStepId = photoStep, value = ItemValue.Step("Get two passport photos", ContentOrigin.USER), recommended = true)
        val s2 = set(rename)
        applier.apply(s2.id, listOf(ApplyChoice("e1", resolution = ConflictResolution.APPLY_ANYWAY))) as ApplyResult.Applied
        assertEquals("CVS on Main St does them.", step(photoStep).description)
        assertTrue(applier.undo(s2.id))
        assertEquals(before, steps())
    }

    @Test fun anApplyRecordFromBeforeRichStepsDoesNotWipeDetailsOnUndo() = runTest {
        seed()
        val s = set(ProposalItem("n1", ItemKind.ADD_STEP, value = ItemValue.Step("Book photo slot", ContentOrigin.USER), recommended = true))
        applier.apply(s.id, listOf(ApplyChoice("n1"))) as ApplyResult.Applied
        // Rewrite the stored record as 0.2.0 would have saved it: snapshots without details, no detailStepIds.
        val stored = sets.get(s.id)!!
        val la = stored.lastApply!!
        val legacy = la.copy(previous = la.previous.copy(steps = la.previous.steps.map { it.copy(description = null, links = emptyList()) }), detailStepIds = emptyList())
        sets.save(stored.copy(lastApply = legacy))
        assertTrue(applier.undo(s.id))
        assertEquals("CVS on Main St does them.", step(photoStep).description)
        assertEquals(listOf(photo), step(photoStep).links)
        assertEquals(4, tasks.getSteps(taskId).size)
    }
}
