package com.lifeadmin.autopilot.share.chatgpt

import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.share.applink.HandoffFragment
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.ImportLinkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The text "Discuss in ChatGPT" prefills: a normal conversation first, the return-link rules after a divider. */
class ChatGptPromptTest {
    private val host = "hassanjaveed84.github.io"
    private val zone = ZoneId.of("America/Chicago")
    private val now = Instant.parse("2026-10-27T15:00:00Z")
    private val task = Task(
        id = "3f2a9c1e-7b4d-4c8e-9a1f-0d2b6e5c7a90", title = "Choose a coffee grinder", notes = "Budget is around \$1,000\nAccount 1234-5678",
        createdAt = now, updatedAt = now, deadlineType = DeadlineType.DATE_ONLY, deadlineLocalDate = LocalDate.of(2026, 11, 14),
    )
    private val steps = listOf(
        TaskStep("s2", task.id, 1, "Read reviews"),
        TaskStep("s1", task.id, 0, "Set a budget", isDone = true),
        TaskStep("s3", task.id, 2, "Visit a shop"),
    )

    private fun prompt(choices: DiscussChoices = DiscussChoices()) = ChatGptPrompt.build(task, steps, choices, host, zone)

    @Test fun itOpensAConversationNotAChecklist() {
        val p = prompt()
        assertTrue(p.startsWith("I'd like your help with something on my to-do list: “Choose a coffee grinder”."))
        assertTrue(p.contains("Let's talk it through first"))
        assertTrue(p.contains("Please don't jump straight to a checklist."))
        // The link instructions come after the conversation part and only apply when asked.
        val divider = p.indexOf("\n---\n")
        assertTrue(divider > 0 && p.indexOf("Open in Autopilot") > divider)
        assertTrue(p.contains("only when I ask you to send this to Autopilot"))
        assertTrue(p.contains("never say something is done unless I told you"))
    }

    @Test fun defaultsShareTitleStepsAndDeadlineButNotNotes() {
        val p = prompt()
        assertTrue(p.contains("- Deadline: Sat 14 Nov 2026"))
        assertTrue(p.contains("- Done: Set a budget"))
        assertTrue(p.contains("- Still to do: Read reviews; Visit a shop"))
        assertFalse(p.contains("Budget is around"))
        assertFalse(p.contains("1234-5678"))
    }

    @Test fun eachChoiceControlsItsField() {
        val withNotes = prompt(DiscussChoices(notes = true))
        assertTrue(withNotes.contains("My notes:\nBudget is around \$1,000\nAccount 1234-5678"))
        val bare = prompt(DiscussChoices(steps = false, deadline = false, notes = false))
        assertFalse(bare.contains("What I have so far"))
        assertFalse(bare.contains("Read reviews"))
        assertFalse(bare.contains("Deadline"))
        // The title and the reference are always there: the return link needs them.
        assertTrue(bare.contains("Choose a coffee grinder"))
        assertTrue(bare.contains("r=${task.id}"))
    }

    @Test fun anExactDeadlineShowsItsLocalTime() {
        val exact = task.copy(deadlineType = DeadlineType.EXACT, deadlineLocalDate = null, deadlineInstant = Instant.parse("2026-11-14T23:00:00Z"))
        assertEquals("Sat 14 Nov 2026, 17:00", ChatGptPrompt.deadlineText(exact, zone))
        assertEquals(null, ChatGptPrompt.deadlineText(task.copy(deadlineType = DeadlineType.NONE, deadlineLocalDate = null), zone))
    }

    @Test fun theLinkStartCarriesTheTaskReferenceAndAValidEncodedTitle() {
        val p = prompt()
        val start = "https://$host/autopilot/import#ap=v2&r=${task.id}&t=Choose%20a%20coffee%20grinder"
        assertTrue(p.contains("\n$start\n"))
        assertTrue(p.contains("space %20 (never +)"))
        assertTrue(p.contains("up to 5 d, 10 s and 5 n"))
        // What ChatGPT is asked to write parses, with the reference intact, once it appends encoded fields.
        val written = start + "&d=" + HandoffFragment.encode("Choose Model A") + "&s=" + HandoffFragment.encode("Compare retailer prices") +
            "&n=" + HandoffFragment.encode("Budget is \$1,000") + ImportLinkParser.END_MARKER
        val h = (ImportLinkParser(host).parse(written) as ImportLinkResult.Valid).handoff
        assertEquals(task.id, h.ref)
        assertEquals("Choose a coffee grinder", h.task)
        assertEquals(listOf("Budget is \$1,000"), h.notes)
    }
}
