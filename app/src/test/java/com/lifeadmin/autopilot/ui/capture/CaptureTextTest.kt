package com.lifeadmin.autopilot.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capture never loses a character: long first lines overflow into the notes at a word boundary. */
class CaptureTextTest {
    @Test fun shortSingleLineIsJustATitle() {
        val s = CaptureText.split("Renew car insurance before 3 Nov")
        assertEquals("Renew car insurance before 3 Nov", s.title)
        assertNull(s.notes)
    }

    @Test fun longSingleLineOverflowsIntoNotesAtAWordBoundaryWithoutLosingText() {
        val words = (1..80).joinToString(" ") { "word$it" } // ~560 chars, one line
        val s = CaptureText.split(words)
        assertTrue("title within limit", s.title.length <= 300)
        assertTrue("title ends on a whole word", words.startsWith(s.title) && words[s.title.length] == ' ')
        assertEquals("nothing lost", words, s.title + " " + s.notes)
    }

    @Test fun lineWithoutSpacesIsCutHardAtTheLimit() {
        val line = "x".repeat(350)
        val s = CaptureText.split(line)
        assertEquals(300, s.title.length)
        assertEquals("x".repeat(50), s.notes)
    }

    @Test fun multilineKeepsEveryLineAndAppendsSeparateNotes() {
        val s = CaptureText.split("Book the train ride\nFor Mia's school trip\nCheck parking too", extraNotes = "Budget £60")
        assertEquals("Book the train ride", s.title)
        assertEquals("For Mia's school trip\nCheck parking too\n\nBudget £60", s.notes)
    }

    @Test fun leadingBlankLinesAndWhitespaceAreIgnored() {
        val s = CaptureText.split("\n\n  Call the bank  \n\n  about the card fee ")
        assertEquals("Call the bank", s.title)
        assertEquals("about the card fee", s.notes)
    }
}
