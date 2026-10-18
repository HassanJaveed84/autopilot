package com.lifeadmin.autopilot.ai

import com.lifeadmin.autopilot.testing.daily
import com.lifeadmin.autopilot.testing.rawPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ProposalValidatorTest {
    private val now = LocalDateTime.of(2026, 10, 14, 10, 0)
    private fun v(raw: RawPlan, text: String = "text", mode: PlanningMode = PlanningMode.CAPTURE, existing: List<String> = emptyList()) =
        ProposalValidator.validate(ProposalValidator.Input(raw, text, mode, now, null, "id", "m", existingSteps = existing, existingTitle = if (mode == PlanningMode.FOLLOW_THROUGH) "Existing" else null))

    @Test fun missingTitleFallsBackToTheUsersOwnWords() {
        val d = v(rawPlan(""), text = "Call the plumber about the leak\nmore details")
        assertEquals("Call the plumber about the leak more details", d.title)
        assertTrue(d.notices.any { it.text.contains("your own words") })
    }

    @Test fun whitespaceAndNewlinesInStepsAreNormalised() {
        val d = v(rawPlan("T", steps = listOf("  Buy\n tickets ", "", "   ")))
        assertEquals(listOf("Buy tickets"), d.steps.map { it.text })
    }

    @Test fun timesMustBe24HourHHMM() {
        assertEquals(LocalTime.of(9, 5), ProposalValidator.parseTime("9:05"))
        listOf("9pm", "24:00", "12:60", "0900", "", "09:00:00").forEach { assertNull(it, ProposalValidator.parseTime(it)) }
    }

    @Test fun duplicateAndUnsortedTimesAreCleaned() {
        val d = v(rawPlan("T", reminder = daily("18:00", "09:00", "18:00", phrase = "text")), text = "text")
        assertEquals(listOf("09:00", "18:00"), d.reminder.times)
    }

    @Test fun priorityNeedsAJustification() {
        assertFalse(v(rawPlan("T", priority = RawPriority("high", ""))).applyPriority)
        assertTrue(v(rawPlan("T", priority = RawPriority("high", "the user said it's urgent"))).applyPriority)
        assertNull(v(rawPlan("T", priority = RawPriority("none", "x"))).priority)
    }

    @Test fun followThroughNeverPreselectsScheduleOrStatusChanges() {
        val d = v(
            rawPlan("Existing", steps = listOf("Old step", "New step"), reminder = daily("09:00", phrase = "text"), waiting = RawWaiting(true, "Bob")),
            text = "text", mode = PlanningMode.FOLLOW_THROUGH, existing = listOf("old step"),
        )
        assertEquals(listOf("New step"), d.steps.map { it.text })
        assertFalse(d.reminder.apply)
        assertFalse(d.waiting.apply)
        assertFalse(d.applyTitle)
        assertFalse(d.applyPriority)
    }

    @Test fun draftRoundTripsThroughSavedState() {
        val d = v(rawPlan("T", steps = listOf("A"), reminder = daily("09:00", phrase = "text")), text = "text")
        assertEquals(d, ProposalDraft.decode(d.encode()))
        assertNull(ProposalDraft.decode("{not json"))
    }
}
