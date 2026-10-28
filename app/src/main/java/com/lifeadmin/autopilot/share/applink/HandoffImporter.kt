package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.proposal.MatchKey
import com.lifeadmin.autopilot.core.AppClock
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.ProposalApplier
import com.lifeadmin.autopilot.data.repository.ProposalSetRepository
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.proposal.ItemStatus
import java.security.MessageDigest

/** Where an incoming handoff goes, before the user has confirmed anything. */
sealed interface HandoffDestination {
    /** The reference names this saved task exactly. [titleDiffers]: ChatGPT used another title. */
    data class Matched(val taskId: String, val titleDiffers: Boolean) : HandoffDestination

    data class Unresolved(val why: Why) : HandoffDestination

    enum class Why {
        /** The link carries no reference (an older link, or ChatGPT left it out). */
        NO_REFERENCE,

        /** The reference is malformed or names no task (deleted, from another phone, or altered). */
        NOT_FOUND,
    }
}

sealed interface HandoffPrepared {
    /** A pending (or, on a repeat link, already settled) proposal set to review. [existed]: from an earlier tap. */
    data class Ready(val taskId: String, val setId: String, val existed: Boolean) : HandoffPrepared

    /** Every step and note is already on the task: nothing to propose, nothing saved. */
    data object NothingNew : HandoffPrepared

    data object TaskGone : HandoffPrepared
}

/**
 * The import side of the ChatGPT round trip. It resolves the destination from the reference only
 * (a title is never enough), saves the canonical proposal set for review and nothing else. Tasks
 * change only when the user applies in Proposal Review, through [ProposalApplier].
 */
class HandoffImporter(
    private val tasks: TaskRepository,
    private val sets: ProposalSetRepository,
    private val applier: ProposalApplier,
    private val clock: AppClock,
) {
    suspend fun resolve(handoff: LinkHandoff): HandoffDestination {
        val ref = handoff.ref ?: return HandoffDestination.Unresolved(HandoffDestination.Why.NO_REFERENCE)
        if (!TaskRef.isWellFormed(ref)) return HandoffDestination.Unresolved(HandoffDestination.Why.NOT_FOUND)
        val task = tasks.getTask(ref) ?: return HandoffDestination.Unresolved(HandoffDestination.Why.NOT_FOUND)
        val differs = key(handoff.task) != key(ImportLinkParser.linkTitle(task.title)) && key(handoff.task) != key(task.title)
        return HandoffDestination.Matched(task.id, differs)
    }

    /** Finds or creates the set for this handoff on [taskId]. Idempotent: a repeat never adds a second set. */
    suspend fun prepare(handoff: LinkHandoff, taskId: String): HandoffPrepared {
        val id = HandoffProposalBridge.setId(taskId, handoff)
        sets.get(id)?.let { return HandoffPrepared.Ready(taskId, id, existed = true) }
        val snapshot = applier.snapshot(taskId) ?: return HandoffPrepared.TaskGone
        val set = HandoffProposalBridge.build(handoff, taskId, snapshot, clock.now().toEpochMilli())
        if (set.items.isEmpty()) return HandoffPrepared.NothingNew
        sets.save(set)
        return HandoffPrepared.Ready(taskId, id, existed = false)
    }

    /**
     * After "Change" moves the handoff to another task: removes the set this screen made for the
     * first one, but only while nothing in it was applied or declined, so no history is lost.
     */
    suspend fun discardIfUntouched(setId: String) {
        val set = sets.get(setId) ?: return
        if (set.lastApply == null && set.items.all { it.status == ItemStatus.PENDING }) sets.delete(setId)
    }

    /**
     * "New task" in the picker, on the user's tap only: a plain Inbox task with ChatGPT's title, no
     * reminders, no deadline. Keyed by the handoff, so a double tap or a repeat link makes one task.
     */
    suspend fun createTask(handoff: LinkHandoff): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("new\u0000" + handoff.task + "\u0000" + HandoffProposalBridge.setId("", handoff)).toByteArray(Charsets.UTF_8))
        val id = "hnt-" + digest.take(12).joinToString("") { "%02x".format(it) }
        return tasks.createCapturedTask(NewTask(handoff.task, status = TaskStatus.INBOX), customPolicy = null, deadline = null, taskId = id).first
    }

    private fun key(s: String) = MatchKey.of(s)
}
