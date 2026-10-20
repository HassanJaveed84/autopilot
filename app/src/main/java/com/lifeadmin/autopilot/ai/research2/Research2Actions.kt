package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.TaskDetails
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.data.repository.ValidationException
import com.lifeadmin.autopilot.domain.model.TaskEditCommand
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The user's edited view of one proposal: included or not, with the title and note as they want them. */
data class ProposalEdit(val id: String, val include: Boolean, val title: String, val note: String?, val addAnyway: Boolean = false)

sealed interface ProposalRow {
    val proposal: Proposal
    val edit: ProposalEdit

    data class AddStep(override val proposal: Proposal, override val edit: ProposalEdit, val title: String, val noteLine: String?) : ProposalRow
    data class AppendNote(override val proposal: Proposal, override val edit: ProposalEdit, val line: String) : ProposalRow
    data class Skip(override val proposal: Proposal, override val edit: ProposalEdit, val reason: String, val duplicate: Boolean = false) : ProposalRow
}

/** Everything the review screen shows and [Research2Actions.apply] executes, computed once. */
data class ProposalPlan(
    val sessionId: String,
    val taskId: String,
    val expectedUpdatedAt: Instant,
    val proposalId: String,
    val rows: List<ProposalRow>,
    val notesBefore: String?,
    val notesAfter: String?,
) {
    val steps: List<ProposalRow.AddStep> get() = rows.filterIsInstance<ProposalRow.AddStep>()
    val notes: List<ProposalRow.AppendNote> get() = rows.filterIsInstance<ProposalRow.AppendNote>()
    val skipped: List<ProposalRow.Skip> get() = rows.filterIsInstance<ProposalRow.Skip>()
    val changeCount: Int get() = steps.size + notes.size
    val isEmpty: Boolean get() = changeCount == 0
}

data class UndoToken2(
    val sessionId: String,
    val taskId: String,
    val proposalId: String,
    val updatedAtAfterApply: Instant,
    val stepIds: List<String>,
    val notesAfter: String?,
    val notesBefore: String?,
    val proposalIds: Set<String>,
)

sealed interface ApplyOutcome2 {
    data class Applied(val applied: List<AppliedProposal>, val undo: UndoToken2?) : ApplyOutcome2
    data class Conflict(val message: String) : ApplyOutcome2
    data class Rejected(val message: String) : ApplyOutcome2
}

/**
 * Turns the user's edited proposal selection into ONE [TaskEditCommand] applied through the same
 * transactional, idempotent path as M3A/M2. Steps are deduplicated against the task unless the user
 * says "Add anyway"; notes are appended, never replaced; evidence (source numbers, check time) rides
 * along in the note line. Deadlines, reminders, priority and status are never touched.
 */
class Research2Actions(
    private val tasks: TaskRepository,
    private val store: ResearchSessionStore,
    private val clock: AppClock,
) {
    fun plan(session: ResearchSession, details: TaskDetails, edits: List<ProposalEdit>, zone: ZoneId = ZoneId.systemDefault()): ProposalPlan {
        val existing = details.steps.map { normalize(it.title) }.toMutableSet()
        val alreadyApplied = session.applied.map { it.proposalId }.toSet()
        val notesBefore = details.task.notes
        val noteLines = ArrayList<String>()
        val rows = ArrayList<ProposalRow>()
        val byId = session.content.proposals.associateBy { it.id }
        for (edit in edits) {
            val p = byId[edit.id] ?: continue
            if (!edit.include) continue
            if (p.id in alreadyApplied) { rows += ProposalRow.Skip(p, edit, "Already applied"); continue }
            val title = TaskRepository.validateStepOrNull(edit.title.ifBlank { p.title })
            if (title == null) { rows += ProposalRow.Skip(p, edit, "The title is empty or too long"); continue }
            val note = edit.note?.trim()?.ifBlank { null }
            when (p.kind) {
                ProposalKind.RESEARCH -> rows += ProposalRow.Skip(p, edit, "Use Continue researching instead")
                ProposalKind.NOTE -> {
                    val line = noteLine(title, note, p, session, zone)
                    if (notesBefore?.contains(line) == true) { rows += ProposalRow.Skip(p, edit, "Already in your notes"); continue }
                    if (buildNotes(notesBefore, noteLines + line).length > TaskRepository.MAX_NOTES) { rows += ProposalRow.Skip(p, edit, "Notes are full"); continue }
                    noteLines += line
                    rows += ProposalRow.AppendNote(p, edit, line)
                }
                else -> {
                    val stepTitle = when (p.kind) {
                        ProposalKind.CHECK -> if (CHECK_VERBS.containsMatchIn(title)) title else "Check: $title"
                        ProposalKind.DECIDE -> if (title.startsWith("Decide", true) || title.startsWith("Choose", true)) title else "Decide: $title"
                        else -> title
                    }.take(TaskRepository.MAX_STEP)
                    val key = normalize(stepTitle)
                    if (key in existing && !edit.addAnyway) { rows += ProposalRow.Skip(p, edit, "A step like this already exists", duplicate = true); continue }
                    existing += key
                    val line = if (note != null || p.refs.isNotEmpty()) noteLine(stepTitle, note, p, session, zone) else null
                    if (line != null) {
                        if (buildNotes(notesBefore, noteLines + line).length <= TaskRepository.MAX_NOTES && notesBefore?.contains(line) != true) noteLines += line
                    }
                    rows += ProposalRow.AddStep(p, edit, stepTitle, line?.takeIf { it in noteLines })
                }
            }
        }
        val notesAfter = if (noteLines.isEmpty()) null else buildNotes(notesBefore, noteLines)
        val applying = rows.filter { it !is ProposalRow.Skip }
        return ProposalPlan(
            session.id, details.task.id, details.task.updatedAt,
            // Same session, same edited selection, same task version → same key (a double tap is a no-op);
            // after an undo the task version differs, so the same selection can be applied again.
            proposalId(session.id, applying.map { it.edit.id + ":" + (it as? ProposalRow.AddStep)?.title.orEmpty() + ":" + ((it as? ProposalRow.AppendNote)?.line ?: (it as? ProposalRow.AddStep)?.noteLine).orEmpty() } + "v=${details.task.updatedAt.toEpochMilli()}"),
            rows, notesBefore, notesAfter,
        )
    }

    /** One transaction: the task edit and the session's applied record commit together or not at all. */
    suspend fun apply(plan: ProposalPlan): ApplyOutcome2 {
        if (plan.isEmpty) return ApplyOutcome2.Rejected("Nothing selected to apply")
        val now = clock.now().toEpochMilli()
        var applied: List<AppliedProposal> = emptyList()
        try {
            tasks.applyTaskEdit(
                TaskEditCommand(
                    taskId = plan.taskId, proposalId = plan.proposalId, expectedUpdatedAt = plan.expectedUpdatedAt,
                    notes = plan.notesAfter, addSteps = plan.steps.map { it.title }, source = SOURCE,
                ),
            ) { addedStepIds ->
                applied = plan.steps.mapIndexed { i, row -> AppliedProposal(row.proposal.id, now, row.title, addedStepIds.getOrNull(i)) } +
                    plan.notes.map { AppliedProposal(it.proposal.id, now, it.line) }
                store.recordApplied(plan.sessionId, applied)
            }
        } catch (e: ValidationException) {
            return if (e.message?.contains("changed") == true || e.message?.contains("already been applied") == true) ApplyOutcome2.Conflict(e.message ?: "The task changed")
            else ApplyOutcome2.Rejected(e.message ?: "Couldn't apply")
        } catch (e: NoSuchElementException) {
            return ApplyOutcome2.Rejected("This task no longer exists")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApplyOutcome2.Rejected("Couldn't save the changes (${e.javaClass.simpleName}). Your task is unchanged.")
        }
        val task = tasks.getTask(plan.taskId)
        val undo = task?.let {
            UndoToken2(plan.sessionId, plan.taskId, plan.proposalId, it.updatedAt, applied.mapNotNull { a -> a.stepId }, plan.notesAfter, plan.notesBefore, applied.map { a -> a.proposalId }.toSet())
        }
        return ApplyOutcome2.Applied(applied, undo)
    }

    fun canUndo(token: UndoToken2, details: TaskDetails?): Boolean =
        details != null && details.task.updatedAt == token.updatedAtAfterApply && !details.task.status.isTerminal

    suspend fun undo(token: UndoToken2): Boolean = try {
        tasks.undoAppliedEdit(token.taskId, token.proposalId, token.updatedAtAfterApply, token.stepIds, token.notesAfter, token.notesBefore) {
            store.clearApplied(token.sessionId, token.proposalIds)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** "• Buy vitamin D: Thorne D-1,000 — 1,000 IU per capsule (sources 2; checked 20 Oct 2026)". */
    private fun noteLine(title: String, note: String?, p: Proposal, session: ResearchSession, zone: ZoneId): String {
        val checked = Instant.ofEpochMilli(p.checkedAtEpochMs).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH))
        val evidence = buildList {
            if (p.refs.isNotEmpty()) add("source${if (p.refs.size == 1) "" else "s"} " + p.refs.joinToString(", "))
            add("checked $checked")
        }.joinToString("; ")
        val body = if (note != null) "$title — $note" else title
        return ("• " + body.take(TaskRepository.MAX_STEP) + " ($evidence)")
    }

    private fun buildNotes(before: String?, lines: List<String>): String {
        val base = before?.trimEnd().orEmpty()
        val block = (if (base.contains(NOTES_HEADER)) "" else "$NOTES_HEADER\n") + lines.joinToString("\n")
        return if (base.isEmpty()) block else "$base\n\n$block"
    }

    companion object {
        const val SOURCE = "ai_research2"
        const val NOTES_HEADER = "From web research:"
        private val CHECK_VERBS = Regex("^(check|confirm|verify|ask|call|make sure|find out|look up)\\b", RegexOption.IGNORE_CASE)

        fun normalize(s: String): String = s.lowercase().replace(Regex("^(check|confirm|verify|decide|choose):\\s*"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** Idempotency key per session and exact edited selection. */
        fun proposalId(sessionId: String, parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(parts.sorted().joinToString("|").toByteArray())
            return "research2:$sessionId:" + digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
