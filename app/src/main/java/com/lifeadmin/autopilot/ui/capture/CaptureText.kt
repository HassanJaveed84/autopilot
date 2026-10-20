package com.lifeadmin.autopilot.ui.capture

import com.lifeadmin.autopilot.data.repository.TaskRepository

/**
 * Turns what the user typed into a title and notes without dropping a character. The title is
 * the first line; if that line is longer than the title limit it is cut at a word boundary and
 * the rest of it leads the notes, followed by any further lines and any separate notes. Nothing
 * is truncated here: text beyond the notes limit is left for validation to report.
 */
object CaptureText {
    data class Split(val title: String, val notes: String?)

    fun split(text: String, extraNotes: String? = null, maxTitle: Int = TaskRepository.MAX_TITLE): Split {
        val lines = text.trim().lines()
        val firstLine = lines.firstOrNull().orEmpty().trim()
        val restLines = lines.drop(1).joinToString("\n").trim()
        val (title, overflow) = if (firstLine.length <= maxTitle) firstLine to "" else cutAtWord(firstLine, maxTitle)
        val notes = listOf(overflow, restLines, extraNotes?.trim().orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n")
        return Split(title, notes.ifBlank { null })
    }

    /** Cuts before [max] characters at the last space in the second half of the allowance, else hard. */
    private fun cutAtWord(line: String, max: Int): Pair<String, String> {
        val cut = line.lastIndexOf(' ', max - 1).takeIf { it >= max / 2 } ?: max
        return line.substring(0, cut).trim() to line.substring(cut).trim()
    }
}
