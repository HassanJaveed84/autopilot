package com.lifeadmin.autopilot.ai.context

import com.lifeadmin.autopilot.ai.PlanningContext
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskEvent
import com.lifeadmin.autopilot.domain.model.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Payload inspection fixtures (handoff §13 "Privacy-safe context serialization", §14 items 1 and 4;
 * PLAN-080/081, INT-012, INT-020–022, INT-064, RES-160). The payload is built from an explicit
 * allow-list, so anything not listed cannot appear.
 */
class ContextAllowListTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-10-23T09:00:00Z")
    private val context = PlanningContext(LocalDateTime.of(2026, 10, 23, 10, 0), zone.id, "+01:00", LocalTime.of(21, 0) to LocalTime.of(8, 0), listOf(LocalTime.of(9, 0)))

    private val task = Task(
        id = "t1", title = "Cancel gym membership", notes = "Member no. 88213-77. Ask about the notice period.", createdAt = now.minusSeconds(86_400 * 20), updatedAt = now,
        eventLabel = "Moving house", eventLocalDate = java.time.LocalDate.of(2026, 11, 12),
    )
    private val details = TaskDetails(
        task, listOf(TaskStep("s1", "t1", 0, "Find the contract"), TaskStep("s2", "t1", 1, "Call the gym", isDone = true)), null, null, emptyList(),
        listOf(TaskEvent("e1", "t1", now.minusSeconds(3600), EventType.SNOOZED, "Snoozed")),
    )

    @Test fun newDraftPlanningSendsOnlyTextAndTimingPreferences() {
        val p = PlanningContextBuilder.newDraft("Cancel the gym before we move", context)
        assertTrue(p.json.contains("Cancel the gym before we move"))
        assertTrue(p.json.contains("\"quietHours\""))
        assertFalse("no task fields on a new draft", p.json.contains("\"task\""))
        assertFalse(p.json.contains("\"findings\""))
        assertEquals(64, p.sha256.length)
    }

    @Test fun existingTaskPayloadIsTheAllowListAndNothingElse() {
        val p = PlanningContextBuilder.task(details, "I keep putting this off", context, now, zone, includeSignals = true, findings = emptyList())
        assertTrue(p.json.contains("Cancel gym membership"))
        assertTrue(p.json.contains("\"done\": true"))
        assertTrue("event date travels with its role, not as a deadline", p.json.contains("\"eventLabel\": \"Moving house\""))
        assertFalse(p.json.contains("\"deadline\""))
        assertTrue(p.json.contains("\"ageDays\": 20"))
        assertTrue(p.json.contains("\"snoozesLast14Days\": 1"))
        assertTrue(p.json.contains("\"priority\": \"NORMAL\""))
        // Internal ids never leave the device.
        assertFalse(p.json.contains("\"t1\""))
        assertFalse(p.json.contains("\"s1\""))
    }

    @Test fun signalsOffRemovesThemAndTheSnapshotFollows() {
        val p = PlanningContextBuilder.task(details, "", context, now, zone, includeSignals = false, findings = emptyList())
        assertFalse(p.json.contains("ageDays"))
        assertFalse(p.json.contains("snoozes"))
        assertTrue(p.lines.any { it.contains("not sent") })
        val snap = PlanningContextBuilder.snapshot(details, now, zone, includeSignals = false)
        assertEquals(-1L, snap.ageDays)
        assertEquals(-1, snap.snoozesLast14Days)
    }

    @Test fun findingsAppearOnlyWhenPassedForThisRequest() {
        val none = PlanningContextBuilder.task(details, "", context, now, zone, includeSignals = true, findings = emptyList())
        assertFalse(none.json.contains("\"findings\""))
        val one = PlanningContextBuilder.task(
            details, "", context, now, zone, includeSignals = true,
            findings = listOf(SelectedFinding("inv1", "turn1", "F1", "30 days notice, from the gym's own terms", "established", "2026-10-20")),
        )
        assertTrue(one.json.contains("30 days notice"))
        assertFalse("no history travels with a finding", one.json.contains("turn2"))
        assertTrue(one.lines.any { it.startsWith("1 research finding") })
    }

    @Test fun turnPayloadCarriesIncludedContextAndSentFindingsOnly() {
        val input = TurnInput(
            objective = "Does FitLife require 30 days notice, and can I cancel by email?",
            constraints = listOf("Answer for the UK"),
            context = listOf(
                ContextItem("title", "Task title", "Cancel gym membership", included = true),
                ContextItem("gym", "Gym name", "FitLife", included = true, requestOnly = true),
                ContextItem("member", "Member number", "88213-77", included = false),
                ContextItem("notes", "Other notes", "Ask about the notice period", included = false),
            ),
            inherited = listOf(
                InheritedFinding("F1", "Notice period is 30 days", "established", "2026-10-20", FindingDisposition.SENT),
                InheritedFinding("F2", "Cancellation fee £25", "indicative", "2026-10-20", FindingDisposition.EXCLUDED),
                InheritedFinding("F3", "Opening hours", "established", "2026-10-20", FindingDisposition.NOT_SENT),
                InheritedFinding("F4", "Current promotion", "established", "2026-10-20", FindingDisposition.FRESH),
            ),
            config = TurnConfig(model = "claude-sonnet-5-5", effort = "medium", depth = "Thorough", searchBudget = 12, spendLimitCents = 50),
        )
        val p = InvestigationTurnBuilder.build(input)
        assertTrue(p.json.contains("FitLife"))
        assertTrue(p.json.contains("\"requestOnly\": true"))
        assertFalse("excluded context is absent", p.json.contains("88213-77"))
        assertFalse(p.json.contains("Ask about the notice period"))
        assertTrue(p.json.contains("Notice period is 30 days"))
        assertFalse("excluded finding is absent", p.json.contains("Cancellation fee"))
        assertFalse("not-sent finding is absent", p.json.contains("Opening hours"))
        assertTrue("fresh checks are named, not reused", p.json.contains("\"fresh_checks\": [\n        \"Current promotion\""))
        assertTrue(p.json.contains("\"excluded_by_you\": 1"))
        assertTrue(p.json.contains("\"earlier_findings_not_sent\": 1"))
        assertTrue(p.json.contains("\"searchBudget\": 12"))
        assertTrue(p.lines.any { it == "Not sent: Member number" })
        assertTrue(InvestigationTurnBuilder.warnings(input).isEmpty())
    }

    @Test fun handEditedObjectiveNamingAnExcludedItemWarns() {
        val input = TurnInput(
            objective = "Check whether member 88213-77 can cancel by email",
            context = listOf(ContextItem("member", "Member number", "88213-77", included = false)),
            inherited = listOf(InheritedFinding("F2", "Cancellation fee £25 applies", "indicative", null, FindingDisposition.EXCLUDED)),
            config = TurnConfig(model = "m", depth = "Quick", searchBudget = 4),
        )
        val w = InvestigationTurnBuilder.warnings(input)
        assertEquals(1, w.size)
        assertTrue(w.single().contains("excluded"))
        val p = InvestigationTurnBuilder.build(input)
        assertFalse("still never re-inserted as context", p.json.contains("\"Member number\""))
    }

    @Test fun samePayloadSameFingerprint() {
        val a = PlanningContextBuilder.task(details, "x", context, now, zone, includeSignals = true, findings = emptyList())
        val b = PlanningContextBuilder.task(details, "x", context, now, zone, includeSignals = true, findings = emptyList())
        assertEquals(a.sha256, b.sha256)
        val c = PlanningContextBuilder.task(details, "y", context, now, zone, includeSignals = true, findings = emptyList())
        assertTrue(a.sha256 != c.sha256)
    }
}
