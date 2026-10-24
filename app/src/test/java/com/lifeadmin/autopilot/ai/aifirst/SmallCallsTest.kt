package com.lifeadmin.autopilot.ai.aifirst

import com.lifeadmin.autopilot.ai.research.ResearchError
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.usage.CallOutcome
import com.lifeadmin.autopilot.ai.usage.InMemoryUsageLedger
import com.lifeadmin.autopilot.ai.usage.UsageFeature
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.domain.model.ContentOrigin
import com.lifeadmin.autopilot.domain.proposal.ItemKind
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import com.lifeadmin.autopilot.domain.proposal.ItemValue
import com.lifeadmin.autopilot.domain.proposal.SetStatus
import com.lifeadmin.autopilot.domain.proposal.StepSnapshot
import com.lifeadmin.autopilot.domain.proposal.TargetSnapshot
import com.lifeadmin.autopilot.testing.FakeCallProvider
import com.lifeadmin.autopilot.testing.ResearchFixtures
import com.lifeadmin.autopilot.testing.SequentialIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The clarification check and the task-suggestion call: no web search, Haiku, ledgered, fail-open / review-only. */
class SmallCallsTest {
    private val clock = AppClock { Instant.parse("2026-10-24T12:00:00Z") }
    private val ledger = InMemoryUsageLedger()
    private val provider = FakeCallProvider()

    @Test fun clarificationParsesOnlyAWellFormedQuestion() {
        assertEquals(null to emptyList<String>(), AfClarifier.parse("CLEAR"))
        assertEquals(null to emptyList<String>(), AfClarifier.parse("I think this is clear enough."))
        val (q, options) = AfClarifier.parse("ASK: Which city?\nOPTION: Washington, DC\nOPTION: Chicago\nOPTION: Chicago")
        assertEquals("Which city?", q)
        assertEquals(listOf("Washington, DC", "Chicago"), options)
        assertNull(AfClarifier.parse("ASK:   ").first)
    }

    @Test fun theCheckSendsOnlyTheQuestionsWithoutSearchAndIsLedgered() = runBlocking {
        provider.enqueue(ResearchFixtures.askWhichCity())
        val c = AfClarifier(provider, ledger, clock, SequentialIds("c")).check("What's the address of Union Station?", listOf("Earlier question"), "key", "thread", "turn", "task")
        val call = provider.calls.single()
        assertEquals(0, call.maxSearches)
        assertEquals(SmallCall.MODEL, call.model)
        assertEquals("Earlier questions in this conversation:\n- Earlier question\n\nQuestion: What's the address of Union Station?", call.user)
        assertTrue(c.checked && c.waitingForAnswer)
        assertEquals(listOf("Washington, DC", "Los Angeles", "Chicago"), c.options)
        val row = ledger.getAll().single()
        assertEquals(UsageFeature.OBJECTIVE_PREP, row.feature)
        assertEquals(CallOutcome.COMPLETE, row.outcome)
        assertEquals(row.estimatedMicros, c.costMicros)
    }

    @Test fun aFailedCheckFailsOpenAndIsStillLedgered() = runBlocking {
        provider.enqueue(CallResult.Failed(ResearchError.Network, 0, null, 10))
        val c = AfClarifier(provider, ledger, clock, SequentialIds("c")).check("q", emptyList(), "key", "thread", "turn", null)
        assertFalse(c.checked)
        assertFalse(c.waitingForAnswer)
        assertTrue(c.note!!.contains("Nothing was searched"))
        assertTrue(c.costIsFloor)
        assertEquals(CallOutcome.FAILED_UNKNOWN, ledger.getAll().single().outcome)
    }

    private val task = TargetSnapshot(rev = 3, title = "Renew passport", status = "ACTIONABLE", steps = listOf(StepSnapshot("s1", 0, "Gather old passport and two photos", false), StepSnapshot("s2", 1, "Book photo", true)), takenAtMs = 0)
    private val turn = AfTurn(
        "turn1", 0, "What documents do I need?", 0, AfStatus.COMPLETE, answer = "Send your passport and one photo. [1]",
        sources = listOf(AfSource(1, "https://www.passports.gov.example/renew", "Renew", listOf("one new photo"))), finishedAtMs = 1_790_000_000_000,
    )

    @Test fun suggestionsBecomeAReviewOnlyProposalSet() = runBlocking {
        provider.enqueue(ResearchFixtures.suggestions())
        val r = AfTaskSuggester(provider, ledger, clock, SequentialIds("s")).suggest("thread", turn, task, "task-1", "key") as SuggestResult.Ready
        val call = provider.calls.single()
        assertEquals(0, call.maxSearches)
        assertTrue(call.user.contains("Current steps: Gather old passport and two photos"))
        assertFalse("done steps are not sent", call.user.contains("Book photo"))
        assertTrue(call.user.contains("Send your passport and one photo. [1]"))
        val set = r.set
        assertEquals(AfTaskSuggester.setId("thread", "turn1"), set.id)
        assertEquals("task-1", set.targetId)
        assertEquals(SetStatus.OPEN, set.status)
        assertEquals(listOf(ItemKind.ADD_STEP, ItemKind.APPEND_NOTE), set.items.map { it.kind })
        assertTrue(set.items.all { it.status == ItemStatus.PENDING && !it.recommended })
        assertEquals(ContentOrigin.RESEARCH, (set.items[0].value as ItemValue.Step).origin)
        assertEquals(listOf("https://www.passports.gov.example/renew"), set.items[0].src!!.findings.single().keys)
        assertEquals(UsageFeature.ASSIST, ledger.getAll().single().feature)
    }

    @Test fun suggestionParsingKeepsAtMostFourDistinctItems() {
        val items = AfTaskSuggester.parse("STEP: a\n- STEP: a\nNOTE: b\nSTEP: c\nSTEP: d\nSTEP: e\nrandom line")
        assertEquals(listOf("a", "b", "c", "d"), items.map { it.second })
        assertTrue(AfTaskSuggester.parse("NONE").isEmpty())
        val empty = AfTaskSuggester.build("thread", turn, task, "task-1", emptyList(), 0)
        assertEquals(SetStatus.CLOSED, empty.status)
    }
}
