package com.lifeadmin.autopilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.local.AppDatabase
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.ImportException
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.ReminderPreset
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.usecase.ScheduleCoordinator
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import com.lifeadmin.autopilot.testing.t
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private val clock = FakeClock(Instant.parse("2026-10-16T14:00:00Z"))
    private var reconciled = 0

    @Before fun setUp() { source = inMemoryDatabase(); target = inMemoryDatabase() }
    @After fun tearDown() { source.close(); target.close() }

    private fun backup(db: AppDatabase) = BackupRepository(db, clock, SequentialIds("imp"), "test", afterImport = { reconciled++ })

    private suspend fun seed(): String {
        val repo = TaskRepository(source, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = repo.createTask(NewTask("Book train ride", notes = "Window seat", priority = Priority.HIGH, status = TaskStatus.ACTIONABLE, preset = ReminderPreset.PERSISTENT))
        repo.addStep(id, "Find dates")
        repo.createTask(NewTask("Renew registration"))
        return backup(source).exportJson()
    }

    @Test fun exportImportRoundTrip() = runTest {
        val json = seed()
        assertTrue(json.contains("\"format\": \"life-admin-autopilot-backup\""))
        val result = backup(target).importJson(json)
        assertEquals(2, result.created)
        assertEquals(1, reconciled)
        val tasks = target.taskDao().getAll().associateBy { it.title }
        assertEquals("Window seat", tasks["Book train ride"]!!.notes)
        val policy = target.policyDao().getForTask(tasks["Book train ride"]!!.id)!!
        assertEquals(listOf(t(9), t(18)), target.policyDao().getSlots(policy.id).map { it.localTime })
        assertEquals(1, target.stepDao().getAll().size)
        // Occurrences are never imported; the scheduler regenerates them.
        assertEquals(0, target.occurrenceDao().getAll().size)
    }

    @Test fun reimportIsIdempotentAndNewerWins() = runTest {
        val json = seed()
        backup(target).importJson(json)
        val again = backup(target).importJson(json)
        assertEquals(0, again.created)
        assertEquals(2, again.skipped)
        assertEquals(2, target.taskDao().count())

        // A newer version of one task replaces the local copy.
        val repo = TaskRepository(source, clock, SequentialIds("x"), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = source.taskDao().getAll().first { it.title == "Renew registration" }.id
        clock.advance(Duration.ofMinutes(5))
        repo.updateDetails(id, "Renew car registration", null, Priority.NORMAL)
        val r = backup(target).importJson(backup(source).exportJson())
        assertEquals(1, r.updated)
        assertEquals("Renew car registration", target.taskDao().get(id)!!.title)
        assertEquals(2, target.taskDao().count())
    }

    @Test fun malformedJsonIsRejected() = runTest {
        expectImportFailure("{not json")
        expectImportFailure("""{"format":"something-else","schemaVersion":1,"exportedAt":"2026-01-01T00:00:00Z"}""")
        expectImportFailure("""{"format":"life-admin-autopilot-backup","schemaVersion":99,"exportedAt":"2026-01-01T00:00:00Z"}""")
    }

    @Test fun partiallyCorruptBackupChangesNothing() = runTest {
        val json = seed()
        // Corrupt the second task's status; the first task is valid but must not be imported either.
        val corrupted = json.replace("\"status\": \"INBOX\"", "\"status\": \"HACKED\"")
        expectImportFailure(corrupted)
        assertEquals(0, target.taskDao().count())
        assertEquals(0, target.stepDao().getAll().size)
    }

    @Test fun orphanReferencesAreRejected() = runTest {
        val json = seed()
        val firstTaskId = source.taskDao().getAll().first().id
        expectImportFailure(json.replace("\"taskId\": \"$firstTaskId\"", "\"taskId\": \"missing-task\""))
        assertEquals(0, target.taskDao().count())
    }

    @Test fun pathLikeOrHugeIdsAreRejected() = runTest {
        val json = seed()
        val id = source.taskDao().getAll().first().id
        expectImportFailure(json.replace(id, "../../etc/passwd"))
    }

    @Test fun oversizedInputIsRejectedBeforeParsing() = runTest {
        val big = ByteArray(BackupRepository.MAX_BYTES + 10) { 'a'.code.toByte() }
        try {
            backup(target).readBounded(big.inputStream())
            fail("expected size rejection")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    private suspend fun expectImportFailure(raw: String) {
        try {
            backup(target).importJson(raw)
            fail("expected import failure")
        } catch (e: ImportException) {
            // ok
        }
        assertEquals(0, reconciled)
    }

    @Test fun researchBriefsRoundTripAndBadOnesRejectTheWholeImport() = runTest {
        val repo = TaskRepository(source, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = repo.createTask(NewTask("Book train ride"))
        val briefs = com.lifeadmin.autopilot.data.repository.ResearchRepository(source, clock, SequentialIds("b"))
        val content = com.lifeadmin.autopilot.ai.research.BriefContent(
            quickAnswer = listOf(com.lifeadmin.autopilot.ai.research.Segment("Tickets go on sale 12 weeks ahead.", listOf(1))),
            moves = listOf(com.lifeadmin.autopilot.ai.research.NextMove("m1", com.lifeadmin.autopilot.ai.research.MoveKind.STEP, "Check the date", "why", listOf(1))),
            findings = listOf(
                com.lifeadmin.autopilot.ai.research.Finding.Sourced("Fact", listOf(1), com.lifeadmin.autopilot.ai.research.SourceTier.WEB),
                com.lifeadmin.autopilot.ai.research.Finding.Unconfirmed("Topic", "note"),
            ),
            sources = listOf(com.lifeadmin.autopilot.ai.research.SourceRef(1, "https://example.com/", "Example", "example.com", com.lifeadmin.autopilot.ai.research.SourceTier.WEB, null, listOf("quote"), 1)),
            queries = listOf("q"), usage = com.lifeadmin.autopilot.ai.research.ResearchUsage(1, 2, 1, "claude-haiku-5-5", 3),
            verified = true, truncated = false, injectionNoticed = false, shared = listOf("Task title"),
        )
        val briefId = briefs.save(id, "When?", content)
        briefs.recordApplied(briefId, listOf(com.lifeadmin.autopilot.ai.research.AppliedMove("m1", 5L, "Check the date", "s1")))
        val json = backup(source).exportJson()
        assertTrue(json.contains("\"research\""))
        assertTrue("never exports the key or a transcript", !json.contains("sk-ant") && !json.contains("x-api-key"))

        backup(target).importJson(json)
        val restored = com.lifeadmin.autopilot.data.repository.ResearchRepository(target, clock, SequentialIds("x")).get(briefId)!!
        assertEquals(content, restored.content)
        assertEquals("When?", restored.question)
        assertEquals(listOf("m1"), restored.applied.map { it.moveId })
        // Re-import is idempotent.
        backup(target).importJson(json)
        assertEquals(1, target.researchDao().getAll().size)

        // A brief pointing at a task not in the file rejects the whole import; nothing changes.
        val at = json.indexOf("\"research\"")
        val broken = json.substring(0, at) + json.substring(at).replace("\"taskId\": \"$id\"", "\"taskId\": \"ghost\"")
        assertTrue("fixture must alter the brief", broken != json)
        val fresh = inMemoryDatabase()
        try {
            backup(fresh).importJson(broken)
            fail("expected rejection")
        } catch (e: ImportException) {
            assertEquals(0, fresh.taskDao().getAll().size)
        } finally { fresh.close() }
    }

    @Test fun sessionsAndUsageRoundTripWithHonestLedgerRules() = runTest {
        val repo = TaskRepository(source, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        val id = repo.createTask(NewTask("Supplements"))
        val r = com.lifeadmin.autopilot.ai.research2.RequirementExtractor.extract(com.lifeadmin.autopilot.testing.Research2Fixtures.SUPPLEMENTS_REQUEST)
        val registry = com.lifeadmin.autopilot.ai.research2.SourceRegistry2(runId = "run1")
        val fx = com.lifeadmin.autopilot.testing.Research2Fixtures.supplementsGather()
        var content = com.lifeadmin.autopilot.ai.research2.SessionContent("t", com.lifeadmin.autopilot.testing.Research2Fixtures.SUPPLEMENTS_REQUEST, r.template, r.requirements, outcome = com.lifeadmin.autopilot.ai.research2.SessionOutcome.INTERRUPTED)
        content = com.lifeadmin.autopilot.ai.research2.SessionAssembler.merge(content, com.lifeadmin.autopilot.ai.research2.ContractParser.parse(fx.segments, fx.searches, registry, r.requirements, 1L), registry.sources()).copy(outcome = com.lifeadmin.autopilot.ai.research2.SessionOutcome.INCOMPLETE_EVIDENCE)
        val sessions = com.lifeadmin.autopilot.data.repository.ResearchSessionRepository(source)
        sessions.save(com.lifeadmin.autopilot.ai.research2.ResearchSession("s1", id, clock.now(), clock.now(), content))
        sessions.recordApplied("s1", listOf(com.lifeadmin.autopilot.ai.research2.AppliedProposal("p-1", 5L, "Buy multivitamin", "st1")))
        val ledger = com.lifeadmin.autopilot.data.repository.RoomUsageLedger(source)
        val base = com.lifeadmin.autopilot.ai.usage.UsageRecord("u1", com.lifeadmin.autopilot.ai.usage.UsageFeature.RESEARCH, "claude-sonnet-5-5", clock.now(), runId = "run1", sessionId = "s1", taskId = id, stage = "GATHER")
        ledger.record(com.lifeadmin.autopilot.ai.usage.UsageRecord.priced(base, com.lifeadmin.autopilot.ai.usage.MeasuredUsage(41_000, 2_600, 0, 0, 0, 5), com.lifeadmin.autopilot.ai.usage.CallOutcome.COMPLETE, 42_000, "msg_1"))
        ledger.record(com.lifeadmin.autopilot.ai.usage.UsageRecord("u2", com.lifeadmin.autopilot.ai.usage.UsageFeature.PLANNING, "claude-haiku-5-5", clock.now()))

        val json = backup(source).exportJson()
        assertTrue(json.contains("\"sessions\"") && json.contains("\"usage\""))
        assertTrue("no prompt text or key in a backup", !json.contains("careful research assistant") && !json.contains("sk-ant"))

        backup(target).importJson(json)
        val restored = com.lifeadmin.autopilot.data.repository.ResearchSessionRepository(target).get("s1")!!
        assertEquals(content, restored.content)
        assertEquals(listOf("p-1"), restored.applied.map { it.proposalId })
        val tl = com.lifeadmin.autopilot.data.repository.RoomUsageLedger(target)
        assertEquals(158_000L, tl.get("u1")!!.estimatedMicros)
        assertEquals("an in-flight row can never finish after a restore", com.lifeadmin.autopilot.ai.usage.CallOutcome.INTERRUPTED, tl.get("u2")!!.outcome)
        // Append-only: a changed record in a re-import does not overwrite the existing row.
        val altered = json.replace("\"estimatedMicros\": 158000", "\"estimatedMicros\": 1")
        assertTrue(altered != json)
        backup(target).importJson(altered)
        assertEquals(158_000L, tl.get("u1")!!.estimatedMicros)
        assertEquals(2, tl.getAll().size)
        // The same provider response under a new local id (a backup from another phone) is not imported twice.
        val renamed = json.replace("\"id\": \"u1\"", "\"id\": \"u1-other-phone\"")
        assertTrue(renamed != json)
        backup(target).importJson(renamed)
        assertEquals("msg_1 is still one row", 2, tl.getAll().size)
        assertEquals(1, tl.getAll().count { it.providerMessageId == "msg_1" })

        // The thread also travels as an investigation (v7); the legacy "sessions" copy is for older app versions.
        assertTrue(json.contains("\"investigations\""))
        val restoredThread = com.lifeadmin.autopilot.data.repository.InvestigationRepository(target).get("s1")!!
        assertEquals(id, restoredThread.taskId)
        assertTrue(restoredThread.meta.legacy)

        // A backup from a pre-M4 app carries only "sessions": it imports into investigations and reads back unchanged (RES-092).
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.toMutableMap()
        root.remove("investigations")
        val legacyOnly = kotlinx.serialization.json.JsonObject(root).toString()
        val older = inMemoryDatabase()
        try {
            backup(older).importJson(legacyOnly)
            val viaSessions = com.lifeadmin.autopilot.data.repository.ResearchSessionRepository(older).get("s1")!!
            assertEquals(content, viaSessions.content)
            assertEquals(listOf("p-1"), viaSessions.applied.map { it.proposalId })
            assertEquals(1, com.lifeadmin.autopilot.data.repository.InvestigationRepository(older).get("s1")!!.turns.size)
        } finally { older.close() }

        // A legacy session pointing at a missing task rejects the whole import, as before.
        val at = legacyOnly.indexOf("\"sessions\"")
        val broken = legacyOnly.substring(0, at) + legacyOnly.substring(at).replaceFirst("\"taskId\":\"$id\"", "\"taskId\":\"ghost\"")
        assertTrue(broken != legacyOnly)
        val fresh = inMemoryDatabase()
        try {
            backup(fresh).importJson(broken)
            fail("expected rejection")
        } catch (e: ImportException) {
            assertEquals(0, fresh.taskDao().getAll().size)
            assertEquals(0, fresh.usageDao().getAll().size)
        } finally { fresh.close() }
    }

    @Test fun investigationThreadsRoundTripKeepTurnsAndSettleRunningOnesAsInterrupted() = runTest {
        val repo = TaskRepository(source, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        val taskId = repo.createTask(NewTask("Renew passport"))
        val inv = com.lifeadmin.autopilot.data.repository.InvestigationRepository(source)
        val now = clock.now().toEpochMilli()
        val objective = com.lifeadmin.autopilot.ai.investigation.Objective("What does renewal cost?", "Find the renewal fee", com.lifeadmin.autopilot.ai.investigation.AskKind.FACT)
        val claim = com.lifeadmin.autopilot.ai.investigation.Claim("c1", "The fee is $130.", com.lifeadmin.autopilot.ai.investigation.ClaimType.FACT, loadBearing = true, status = com.lifeadmin.autopilot.ai.investigation.ClaimStatus.ESTABLISHED, turnId = "k1")
        val result = com.lifeadmin.autopilot.ai.investigation.TurnResult(
            com.lifeadmin.autopilot.ai.investigation.Answer("Fee", "Answered", listOf(com.lifeadmin.autopilot.ai.investigation.AnswerSegment("The fee is $130.", listOf("c1")))),
            com.lifeadmin.autopilot.ai.investigation.ResultState.ANSWERED, com.lifeadmin.autopilot.ai.investigation.TurnEnding.COMPLETE, listOf(claim), emptyList(), finishedAtMs = now,
        )
        val done = com.lifeadmin.autopilot.ai.investigation.Turn("k1", 0, com.lifeadmin.autopilot.ai.investigation.TurnKind.INITIAL, com.lifeadmin.autopilot.ai.investigation.TurnState.DONE, now, objective, result = result)
        val running = com.lifeadmin.autopilot.ai.investigation.Turn("k2", 1, com.lifeadmin.autopilot.ai.investigation.TurnKind.CONTINUATION, com.lifeadmin.autopilot.ai.investigation.TurnState.RUNNING, now + 1, objective)
        inv.save(com.lifeadmin.autopilot.ai.investigation.Investigation("linked", com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin.TASK, taskId, null, now, now, com.lifeadmin.autopilot.ai.investigation.InvestigationMeta("Passport fee", "What does renewal cost?"), listOf(done, running)))
        inv.save(com.lifeadmin.autopilot.ai.investigation.Investigation("solo", com.lifeadmin.autopilot.ai.investigation.InvestigationOrigin.STANDALONE, null, null, now, now, com.lifeadmin.autopilot.ai.investigation.InvestigationMeta("Union Station", "address of Union Station"), listOf(done.copy(id = "u1"))))

        val json = backup(source).exportJson()
        assertTrue("a native thread has no legacy session copy", !json.contains("\"sessions\": [\n    {"))
        backup(target).importJson(json)
        val tInv = com.lifeadmin.autopilot.data.repository.InvestigationRepository(target)
        val linked = tInv.get("linked")!!
        assertEquals(taskId, linked.taskId)
        assertEquals(listOf("k1", "k2"), linked.turns.map { it.id })
        assertEquals("the finished turn is byte-for-byte", result, linked.turns[0].result)
        assertEquals("a turn still running in a backup can never finish", com.lifeadmin.autopilot.ai.investigation.TurnState.INTERRUPTED, linked.turns[1].state)
        assertEquals("INTERRUPTED", target.investigationDao().get("linked")!!.state)
        assertNull(tInv.get("solo")!!.taskId)
        assertEquals(1, tInv.get("solo")!!.turns.size)

        // A thread whose task is not in the backup is kept as standalone history and says so; nothing is rejected.
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.toMutableMap()
        root["tasks"] = kotlinx.serialization.json.JsonArray(emptyList())
        root["steps"] = kotlinx.serialization.json.JsonArray(emptyList()); root["policies"] = kotlinx.serialization.json.JsonArray(emptyList()); root["events"] = kotlinx.serialization.json.JsonArray(emptyList())
        val fresh = inMemoryDatabase()
        try {
            backup(fresh).importJson(kotlinx.serialization.json.JsonObject(root).toString())
            val orphan = com.lifeadmin.autopilot.data.repository.InvestigationRepository(fresh).get("linked")!!
            assertNull(orphan.taskId)
            assertEquals(2, orphan.turns.size)
            assertTrue(orphan.meta.associations.single().note.contains("not in the backup"))
        } finally { fresh.close() }

        // A tampered turn payload rejects the whole file.
        val tampered = json.replace("\"index\": 1", "\"index\": 7")
        assertTrue(tampered != json)
        val fresh2 = inMemoryDatabase()
        try {
            backup(fresh2).importJson(tampered)
            fail("expected rejection")
        } catch (e: ImportException) {
            assertEquals(0, fresh2.taskDao().getAll().size)
        } finally { fresh2.close() }
    }

    @Test fun draftsProposalSetsAndProvenanceRoundTripAndOlderBackupsStillImport() = runTest {
        val repo = TaskRepository(source, clock, SequentialIds(), { CHICAGO }, ScheduleCoordinator.Direct)
        val taskId = repo.createTask(NewTask("Renew passport", status = TaskStatus.ACTIONABLE))
        val drafts = com.lifeadmin.autopilot.data.repository.DraftRepository(source, clock, SequentialIds("d"))
        val draft = drafts.create("Book the dentist")
        drafts.attachPlanningResult(draft.id, "{\"proposalId\":\"p1\"}", "claude-haiku-5-5")
        val sets = com.lifeadmin.autopilot.data.repository.ProposalSetRepository(source)
        val applier = com.lifeadmin.autopilot.data.repository.ProposalApplier(source, repo, sets, clock, SequentialIds("x"))
        val snap = applier.snapshot(taskId)!!
        sets.save(
            com.lifeadmin.autopilot.domain.proposal.ProposalSet(
                "set1", com.lifeadmin.autopilot.domain.proposal.ProposalSource.RESEARCH, com.lifeadmin.autopilot.domain.proposal.TargetKind.TASK, taskId,
                investigationId = "inv1", turnId = "turn1", madeAtMs = clock.now().toEpochMilli(), baseOriginal = snap, base = snap,
                items = listOf(
                    com.lifeadmin.autopilot.domain.proposal.ProposalItem("a", com.lifeadmin.autopilot.domain.proposal.ItemKind.ADD_STEP, value = com.lifeadmin.autopilot.domain.proposal.ItemValue.Step("Book appointment")),
                    com.lifeadmin.autopilot.domain.proposal.ProposalItem("r", com.lifeadmin.autopilot.domain.proposal.ItemKind.SET_REMINDER, value = com.lifeadmin.autopilot.domain.proposal.ItemValue.Reminder("DAILY_TIMES", listOf("18:00")), material = true),
                ),
            ),
        )
        applier.apply("set1", listOf(com.lifeadmin.autopilot.domain.proposal.ApplyChoice("a")))
        val json = backup(source).exportJson()
        assertTrue(json.contains("\"drafts\""))
        assertTrue(json.contains("\"proposals\""))
        assertTrue(json.contains("\"provenance\""))

        val result = backup(target).importJson(json)
        assertEquals(1, result.created)
        val importedDraft = com.lifeadmin.autopilot.data.repository.DraftRepository(target, clock, SequentialIds("e")).get(draft.id)!!
        assertEquals("{\"proposalId\":\"p1\"}", importedDraft.content.planningResult)
        val importedSet = com.lifeadmin.autopilot.data.repository.ProposalSetRepository(target).get("set1")!!
        assertEquals(com.lifeadmin.autopilot.domain.proposal.ItemStatus.APPLIED, importedSet.item("a")!!.status)
        assertEquals(snap, importedSet.baseOriginal)
        val step = target.stepDao().getForTask(taskId).first { it.title == "Book appointment" }
        assertEquals("RESEARCH", step.origin)
        val record = com.lifeadmin.autopilot.data.repository.ProposalSetRepository(target).provenance(step.provenanceId!!)!!
        assertEquals("inv1", record.investigationId)
        assertEquals(1, target.taskDao().get(taskId)!!.rev.let { if (it > 0) 1 else 0 })

        // A pre-v6 backup has none of the new fields or arrays and must import unchanged.
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject.toMutableMap()
        root.remove("drafts"); root.remove("proposals"); root.remove("provenance")
        fun strip(key: String, vararg fields: String) {
            root[key] = kotlinx.serialization.json.JsonArray(root.getValue(key).jsonArray.map { e -> kotlinx.serialization.json.JsonObject(e.jsonObject.filterKeys { it !in fields }) })
        }
        strip("tasks", "rev", "eventLocalDate", "eventLabel", "intendedLocalDate", "targetLocalDate", "targetBasis")
        strip("steps", "origin", "provenanceId")
        val legacy = kotlinx.serialization.json.JsonObject(root).toString()
        assertTrue(!legacy.contains("\"drafts\""))
        val fresh = inMemoryDatabase()
        val r2 = backup(fresh).importJson(legacy)
        assertEquals(1, r2.created)
        assertEquals("USER", fresh.stepDao().getForTask(taskId).first().origin)
        assertEquals(0, fresh.draftDao().getAll().size)
        fresh.close()
    }

    @Test fun aProposalSetForAMissingTargetRejectsTheImport() = runTest {
        val json = seed()
        val tampered = json.replace("\"provenance\": []", "\"provenance\": []").replace(
            "\"proposals\": []",
            "\"proposals\": [{\"id\":\"set9\",\"source\":\"PLANNING\",\"targetKind\":\"TASK\",\"targetId\":\"missing\",\"madeAt\":\"2026-10-23T09:00:00Z\",\"status\":\"OPEN\",\"payload\":\"{}\"}]",
        )
        assertTrue(tampered != json)
        expectImportFailure(tampered)
    }
}
