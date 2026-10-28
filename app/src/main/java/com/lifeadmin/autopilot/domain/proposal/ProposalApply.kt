package com.lifeadmin.autopilot.domain.proposal

/** What the user decided about a conflicting item in Proposal Review. */
enum class ConflictResolution {
    /** No decision yet; a conflict blocks the apply and sends the set back to review. */
    NONE,

    /** Apply this change on top of the intervening change. */
    APPLY_ANYWAY,

    /** Keep the user's version; the item is declined (handoff §5). */
    KEEP_YOURS,
}

/**
 * One accepted item. [value] carries the user's edit when they changed the proposed text before
 * accepting (editing never accepts by itself; PLAN-071).
 */
data class ApplyChoice(val itemId: String, val value: ItemValue? = null, val resolution: ConflictResolution = ConflictResolution.NONE)

sealed interface ApplyResult {
    /** Everything chosen was written in one transaction. */
    data class Applied(val revBefore: Int, val revAfter: Int, val appliedItemIds: List<String>, val declinedItemIds: List<String>, val set: ProposalSet) : ApplyResult

    /** The target moved between review and apply (or a chosen item is blocked): nothing was written. */
    data class NeedsReview(val checks: List<ItemCheck>) : ApplyResult

    /** Invalid request or target gone; nothing was written. */
    data class Rejected(val message: String) : ApplyResult
}
