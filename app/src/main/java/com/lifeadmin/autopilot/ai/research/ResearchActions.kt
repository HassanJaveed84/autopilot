package com.lifeadmin.autopilot.ai.research

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.ResearchBrief
import com.lifeadmin.autopilot.data.repository.ResearchRepository
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.TaskEditCommand
import java.security.MessageDigest
import java.time.Instant

/** One proposed change as it will be applied, or why it will be skipped. */
sealed interface ApplyRow {
    val move: NextMove

    data class AddStep(override val move: NextMove, val title: String) : ApplyRow
    data class AppendNote(override val move: NextMove, val line: String) : ApplyRow
    data class Skip(override val move: NextMove, val reason: String) : ApplyRow
}

/** Everything the review screen shows and [ResearchActions.apply] executes, computed once. */
data class ApplyPlan(
    val briefId: String,
    val taskId: String,
    /** Rejects the apply if the task changed after this plan was computed. */
    val expectedUpdatedAt: Instant,
    val proposalId: String,
    val rows: List<ApplyRow>,
    val notesBefore: String?,
    /** Notes after the apply, or null when no note is added. */
    val notesAfter: String?,
) {
    val steps: List<ApplyRow.AddStep> get() = rows.filterIsInstance<ApplyRow.AddStep>()
    val notes: List<ApplyRow.AppendNote> get() = rows.filterIsInstance<ApplyRow.AppendNote>()
    val skipped: List<ApplyRow.Skip> get() = rows.filterIsInstance<ApplyRow.Skip>()
    val changeCount: Int get() = steps.size + notes.size
    val isEmpty: Boolean get() = changeCount == 0
}

/** What an Undo needs; only valid while the task stays exactly as the apply left it. */
data class UndoToken(
    val briefId: String,
    val taskId: String,
    val proposalId: String,
    val updatedAtAfterApply: Instant,
    val stepIds: List<String>,
    val notesAfter: String?,
    val notesBefore: String?,
    val moveIds: Set<String>,
)

sealed interface ApplyOutcome {
    data class Applied(val applied: List<AppliedMove>, val undo: UndoToken?) : ApplyOutcome

    /** The task changed since the brief was reviewed, or this selection was already applied. */
    data class Conflict(val message: String) : ApplyOutcome
    data class Rejected(val message: String) : ApplyOutcome
}

/**
 * Turns selected moves into one [TaskEditCommand] and applies it through the same transactional,
 * idempotent path as AI planning. Steps are deduplicated against the task; notes are appended, never
 * replaced. Deadlines, reminders, status and priority are never changed by research (M3A).
 */
class ResearchActions(
    private val tasks: TaskRepository,
    private val briefs: ResearchRepository,
    private val clock: AppClock,
) {
    fun plan(brief: ResearchBrief, details: TaskDetails, selected: Set<String>): ApplyPlan {
        val existing = details.steps.map { normalize(it.title) }.toMutableSet()
        val alreadyApplied = brief.applied.map { it.moveId }.toSet()
        val notesBefore = details.task.notes
        val noteLines = ArrayList<String>()
        val rows = ArrayList<ApplyRow>()
        for (move in brief.content.moves) {
            if (move.id !in selected) continue
            if (move.id in alreadyApplied) { rows += ApplyRow.Skip(move, "Already applied"); continue }
            when (move.kind) {
                MoveKind.STEP, MoveKind.CHECK -> {
                    val title = stepTitle(move)
                    val key = normalize(title)
                    if (key in existing) { rows += ApplyRow.Skip(move, "A step like this already exists"); continue }
                    existing += key
                    rows += ApplyRow.AddStep(move, title)
                }
                MoveKind.NOTE -> {
                    val line = noteLine(move)
                    if (notesBefore?.contains(line) == true) { rows += ApplyRow.Skip(move, "Already in your notes"); continue }
                    val candidate = buildNotes(notesBefore, noteLines + line)
                    if (candidate.length > TaskRepository.MAX_NOTES) { rows += ApplyRow.Skip(move, "Notes are full"); continue }
                    noteLines += line
                    rows += ApplyRow.AppendNote(move, line)
                }
            }
        }
        val notesAfter = if (noteLines.isEmpty()) null else buildNotes(notesBefore, noteLines)
        val selectedIds = rows.filter { it !is ApplyRow.Skip }.map { it.move.id }.sorted()
        return ApplyPlan(
            briefId = brief.id, taskId = details.task.id, expectedUpdatedAt = details.task.updatedAt,
            proposalId = proposalId(brief.id, selectedIds), rows = rows, notesBefore = notesBefore, notesAfter = notesAfter,
        )
    }

    /**
     * Applies the plan in ONE database transaction: the task edit (steps, notes, history event) and
     * the brief's applied-moves record commit together or not at all. A retry after a crash, a
     * double tap or a stale plan hits the proposal-id / updatedAt guards and changes nothing.
     */
    suspend fun apply(plan: ApplyPlan): ApplyOutcome {
        if (plan.isEmpty) return ApplyOutcome.Rejected("Nothing selected to apply")
        val now = clock.now().toEpochMilli()
        var applied: List<AppliedMove> = emptyList()
        try {
            tasks.applyTaskEdit(
                TaskEditCommand(
                    taskId = plan.taskId,
                    proposalId = plan.proposalId,
                    expectedUpdatedAt = plan.expectedUpdatedAt,
                    notes = plan.notesAfter,
                    addSteps = plan.steps.map { it.title },
                    source = SOURCE,
                ),
            ) { addedStepIds ->
                // Steps are inserted in plan order, so ids line up with the plan's step rows.
                applied = plan.steps.mapIndexed { i, row -> AppliedMove(row.move.id, now, row.title, addedStepIds.getOrNull(i)) } +
                    plan.notes.map { AppliedMove(it.move.id, now) }
                briefs.recordApplied(plan.briefId, applied)
            }
        } catch (e: ValidationException) {
            return if (e.message?.contains("changed") == true || e.message?.contains("already been applied") == true) {
                ApplyOutcome.Conflict(e.message ?: "The task changed")
            } else {
                ApplyOutcome.Rejected(e.message ?: "Couldn't apply")
            }
        } catch (e: NoSuchElementException) {
            return ApplyOutcome.Rejected("This task no longer exists")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The transaction rolled back: the task is unchanged and nothing was recorded.
            return ApplyOutcome.Rejected("Couldn't save the changes (${e.javaClass.simpleName}). Your task is unchanged.")
        }
        val task = tasks.getTask(plan.taskId)
        val undo = task?.let {
            UndoToken(
                briefId = plan.briefId, taskId = plan.taskId, proposalId = plan.proposalId, updatedAtAfterApply = it.updatedAt,
                stepIds = applied.mapNotNull { a -> a.stepId }, notesAfter = plan.notesAfter, notesBefore = plan.notesBefore,
                moveIds = applied.map { a -> a.moveId }.toSet(),
            )
        }
        return ApplyOutcome.Applied(applied, undo)
    }

    /** Undo is offered only while the task is exactly as the apply left it. */
    fun canUndo(token: UndoToken, details: TaskDetails?): Boolean =
        details != null && details.task.updatedAt == token.updatedAtAfterApply && !details.task.status.isTerminal

    /**
     * Reverts exactly this apply, in one transaction with the brief's bookkeeping, and only while the
     * task is still as the apply left it; returns false (and changes nothing) otherwise.
     */
    suspend fun undo(token: UndoToken): Boolean = try {
        tasks.undoAppliedEdit(token.taskId, token.proposalId, token.updatedAtAfterApply, token.stepIds, token.notesAfter, token.notesBefore) {
            briefs.clearApplied(token.briefId, token.moveIds)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false // rolled back; the task keeps the applied changes and the record stays consistent with it
    }

    private fun stepTitle(move: NextMove): String {
        val text = move.text.trim().trimEnd('.')
        val title = if (move.kind == MoveKind.CHECK && !CHECK_VERBS.containsMatchIn(text)) "Check: $text" else text
        return title.take(TaskRepository.MAX_STEP)
    }

    private fun noteLine(move: NextMove): String = "• " + move.text.trim().trimEnd('.').take(TaskRepository.MAX_STEP)

    private fun buildNotes(before: String?, lines: List<String>): String {
        val base = before?.trimEnd().orEmpty()
        val block = (if (base.contains(NOTES_HEADER)) "" else "$NOTES_HEADER\n") + lines.joinToString("\n")
        return if (base.isEmpty()) block else "$base\n\n$block"
    }

    companion object {
        const val SOURCE = "ai_research"
        const val NOTES_HEADER = "From web research:"
        private val CHECK_VERBS = Regex("^(check|confirm|verify|ask|call|make sure|find out|look up)\\b", RegexOption.IGNORE_CASE)

        fun normalize(s: String): String = s.lowercase().replace(Regex("^(check|confirm|verify):\\s*"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** Idempotency key per brief and selection; applying the same selection twice is a no-op error. */
        fun proposalId(briefId: String, selectedMoveIds: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(selectedMoveIds.sorted().joinToString(",").toByteArray())
            return "research:$briefId:" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
