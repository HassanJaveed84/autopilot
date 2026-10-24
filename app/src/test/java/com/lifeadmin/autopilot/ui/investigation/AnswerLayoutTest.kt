package com.lifeadmin.autopilot.ui.investigation

import com.lifeadmin.autopilot.ai.aifirst.AfSegment
import com.lifeadmin.autopilot.ui.investigation.AnswerLayout.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Display structure only: Claude's words reach the screen unchanged, with each citation on the text the provider attached it to. */
class AnswerLayoutTest {
    @Test fun headingsBulletsAndCitationMarksKeepTheProvidersSpans() {
        val blocks = AnswerLayout.blocks(listOf(
            AfSegment("## What to send\n- "),
            AfSegment("Your passport and one photo", listOf(1)),
            AfSegment("\n- "),
            AfSegment("A name-change document if needed", listOf(2, 1)),
            AfSegment("\n\nI'd check **eligibility** first."),
        ))
        assertEquals(4, blocks.size)
        assertEquals("What to send", AnswerLayout.plain((blocks[0] as Block.Heading).runs))
        val first = blocks[1] as Block.Bullet
        assertEquals("Your passport and one photo", AnswerLayout.plain(first.runs))
        assertEquals(listOf(1), first.runs.single { it.cited }.marks)
        assertEquals(listOf(2, 1), (blocks[2] as Block.Bullet).runs.single { it.cited }.marks)
        val p = blocks[3] as Block.Paragraph
        assertEquals("I'd check eligibility first.", AnswerLayout.plain(p.runs))
        assertTrue("uncited judgment stays marked as uncited", p.runs.none { it.cited })
    }

    @Test fun tablesSplitIntoCellsThatKeepTheirCitations() {
        val blocks = AnswerLayout.blocks(listOf(
            AfSegment("| | Thorne | Pure |\n|---|---|---|\n| Dose | "),
            AfSegment("Two capsules", listOf(1)),
            AfSegment(" | "),
            AfSegment("One capsule", listOf(2)),
            AfSegment(" |\n\nNeither is clearly better."),
        ))
        val table = blocks[0] as Block.Table
        assertEquals(2, table.rows.size)
        assertEquals(listOf("", "Thorne", "Pure"), table.rows[0].map { AnswerLayout.plain(it) })
        val row = table.rows[1]
        assertEquals(listOf("Dose", "Two capsules", "One capsule"), row.map { AnswerLayout.plain(it) })
        assertEquals(listOf(1), row[1].flatMap { it.marks })
        assertEquals(listOf(2), row[2].flatMap { it.marks })
        assertEquals("Neither is clearly better.", AnswerLayout.plain((blocks[1] as Block.Paragraph).runs))
    }

    @Test fun everyWordOfTheAnswerIsShown() {
        val segments = listOf(AfSegment("Plain text with a [bracket] and a URL https://x.example/a.", emptyList()), AfSegment(" Cited part.", listOf(3)))
        val shown = AnswerLayout.blocks(segments).joinToString("") { (it as Block.Paragraph).runs.let(AnswerLayout::plain) }
        assertEquals("Plain text with a [bracket] and a URL https://x.example/a. Cited part.", shown)
    }
}
