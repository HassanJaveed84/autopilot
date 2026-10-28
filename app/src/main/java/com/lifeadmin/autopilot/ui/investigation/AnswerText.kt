package com.lifeadmin.autopilot.ui.investigation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifeadmin.autopilot.ai.aifirst.AfSegment
import com.lifeadmin.autopilot.ui.theme.AutopilotTheme

/**
 * Display model of an answer: the provider's segments split into lines, each line a list of runs that
 * know whether a provider citation is attached. Pure, so the block structure is unit-testable.
 * Only presentation is derived here (headings, bullets, tables, bold); the words are Claude's, unchanged.
 */
object AnswerLayout {
    data class Run(val text: String, val cited: Boolean, val marks: List<Int> = emptyList())

    sealed interface Block {
        data class Heading(val runs: List<Run>) : Block
        data class Paragraph(val runs: List<Run>) : Block
        data class Bullet(val marker: String, val runs: List<Run>) : Block
        data class Table(val rows: List<List<List<Run>>>) : Block
        data object Rule : Block
    }

    private val ORDERED = Regex("""^(\d{1,2})[.)]\s+""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val TABLE_SEPARATOR = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

    fun blocks(segments: List<AfSegment>): List<Block> {
        // Lines of runs. A cited segment's marks attach to the last run of its text, before trailing whitespace.
        val lines = ArrayList<MutableList<Run>>().apply { add(ArrayList()) }
        for (s in segments) {
            val cited = s.sources.isNotEmpty()
            val body = if (cited) s.text.trimEnd() else s.text
            val parts = body.split('\n')
            parts.forEachIndexed { i, p ->
                if (i > 0) lines.add(ArrayList())
                val last = i == parts.lastIndex
                if (p.isNotEmpty() || (last && cited)) lines.last().add(Run(p, cited, if (last && cited) s.sources else emptyList()))
            }
            if (cited) s.text.substring(body.length).count { it == '\n' }.let { n -> repeat(n) { lines.add(ArrayList()) } }
        }
        val out = ArrayList<Block>()
        var table: MutableList<List<List<Run>>>? = null
        fun flushTable() { table?.let { if (it.isNotEmpty()) out += Block.Table(it) }; table = null }
        for (runs in lines) {
            val raw = runs.joinToString("") { it.text }
            val t = raw.trim()
            if (t.startsWith("|")) {
                if (!TABLE_SEPARATOR.matches(t)) (table ?: ArrayList<List<List<Run>>>().also { table = it }).add(cells(runs))
                continue
            }
            flushTable()
            when {
                t.isEmpty() -> if (runs.none { it.marks.isNotEmpty() }) continue else out += Block.Paragraph(runs)
                t == "---" || t == "***" -> out += Block.Rule
                HEADING.containsMatchIn(t) -> out += Block.Heading(drop(runs, raw.indexOf('#') + HEADING.find(t)!!.value.length))
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> out += Block.Bullet("•", drop(runs, raw.indexOf(t[0]) + 2))
                ORDERED.containsMatchIn(t) -> ORDERED.find(t)!!.let { m -> out += Block.Bullet(m.groupValues[1] + ".", drop(runs, raw.indexOf(t[0]) + m.value.length)) }
                else -> out += Block.Paragraph(drop(runs, raw.length - raw.trimStart().length))
            }
        }
        flushTable()
        return out
    }

    /** Removes the first [n] characters across runs (a Markdown prefix such as "- " or "## "). */
    fun drop(runs: List<Run>, n: Int): List<Run> {
        var left = n
        val out = ArrayList<Run>()
        for (r in runs) {
            if (left <= 0) { out += r; continue }
            if (r.text.length <= left) { left -= r.text.length; if (r.marks.isNotEmpty()) out += r.copy(text = ""); continue }
            out += r.copy(text = r.text.substring(left)); left = 0
        }
        return out
    }

    /** Splits a table line's runs at `|`, keeping each cell's citation state. */
    private fun cells(runs: List<Run>): List<List<Run>> {
        val cells = ArrayList<MutableList<Run>>().apply { add(ArrayList()) }
        for (r in runs) {
            val parts = r.text.split('|')
            parts.forEachIndexed { i, p ->
                if (i > 0) cells.add(ArrayList())
                if (p.isNotEmpty() || (i == parts.lastIndex && r.marks.isNotEmpty())) cells.last().add(Run(p, r.cited, if (i == parts.lastIndex) r.marks else emptyList()))
            }
        }
        // Leading and trailing pipes leave empty edge cells.
        if (cells.firstOrNull()?.all { it.text.isBlank() && it.marks.isEmpty() } == true) cells.removeAt(0)
        if (cells.lastOrNull()?.all { it.text.isBlank() && it.marks.isEmpty() } == true) cells.removeAt(cells.lastIndex)
        return cells.map { c -> trim(c) }
    }

    private fun trim(runs: List<Run>): List<Run> = runs.mapIndexed { i, r -> r.copy(text = r.text.let { if (i == 0) it.trimStart() else it }.let { if (i == runs.lastIndex) it.trimEnd() else it }) }

    /** Plain text of runs with `**` emphasis markers removed, for semantics and tests. */
    fun plain(runs: List<Run>): String = runs.joinToString("") { it.text }.replace("**", "")
}

/**
 * Claude's answer with the provider's citations as tappable `[n]` marks. Text with a citation
 * attached is ink; text without one is a quieter ink — it is Claude's own wording, inference or
 * judgment, not something a cited page was checked against.
 */
@Composable
fun AnswerText(segments: List<AfSegment>, onSource: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = AutopilotTheme.colors
    val blocks = remember(segments) { AnswerLayout.blocks(segments) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is AnswerLayout.Block.Heading -> Text(annotated(b.runs, c.ink, c.ink2, c.sunken, onSource, bold = true), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
                is AnswerLayout.Block.Paragraph -> Text(annotated(b.runs, c.ink, c.ink2, c.sunken, onSource), style = MaterialTheme.typography.bodyLarge)
                is AnswerLayout.Block.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = c.ink2)
                    Text(annotated(b.runs, c.ink, c.ink2, c.sunken, onSource), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                is AnswerLayout.Block.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    b.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.width((row.size * CELL_WIDTH).dp))
                        Row(Modifier.let { if (i == 0) it.background(c.sunken) else it }) {
                            row.forEach { cell -> Text(annotated(cell, c.ink, c.ink2, c.sunken, onSource, bold = i == 0), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(CELL_WIDTH.dp).padding(8.dp)) }
                        }
                    }
                }
                AnswerLayout.Block.Rule -> HorizontalDivider(color = c.line)
            }
        }
    }
}

private const val CELL_WIDTH = 150

private fun annotated(runs: List<AnswerLayout.Run>, cited: Color, uncited: Color, markBg: Color, onSource: (Int) -> Unit, bold: Boolean = false): AnnotatedString = buildAnnotatedString {
    var strong = bold
    for (r in runs) {
        val parts = r.text.split("**")
        parts.forEachIndexed { i, p ->
            if (i > 0) strong = !strong || bold
            if (p.isNotEmpty()) withStyle(SpanStyle(color = if (r.cited) cited else uncited, fontWeight = if (strong) FontWeight.SemiBold else null)) { append(p) }
        }
        for (n in r.marks) {
            append(" ")
            withLink(LinkAnnotation.Clickable("source_$n", TextLinkStyles(SpanStyle(color = cited, background = markBg, fontFamily = FontFamily.Monospace, fontSize = 12.sp))) { onSource(n) }) { append("[$n]") }
        }
    }
}
