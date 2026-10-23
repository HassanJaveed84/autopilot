package com.lifeadmin.autopilot.ai.investigation

import java.time.Duration

/**
 * Derives every claim's status from the evidence attached to that claim (RES-042–046, 070).
 * Pure and configuration-free: the same evidence yields the same statuses whatever model or
 * depth gathered it (RES-064). The model's own status words are never an input.
 */
object ClaimAssessor {
    /** Evidence for a claim marked volatile is too old to establish it after this long (RES-046, per-claim flag, not a category default). */
    val VOLATILE_FRESH_FOR: Duration = Duration.ofDays(7)

    fun assess(claims: List<Claim>, nowMs: Long): List<Claim> {
        // Premise statuses first, then derived claims that rest on them; two passes settle chains of judgments.
        var current = claims.map { if (it.type.derived) it else assessOne(it, nowMs) }
        repeat(3) { current = current.map { if (it.type.derived) derived(it, current) else it } }
        return current
    }

    fun assessOne(c: Claim, nowMs: Long): Claim {
        if (c.superseded) return c.copy(status = if (c.status == ClaimStatus.ESTABLISHED) ClaimStatus.ESTABLISHED else c.status, statusReason = c.statusReason.ifBlank { "Replaced by a later finding" })
        if (!c.checked) return c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "Not checked before the run stopped")
        if (c.dependsOnUser) return c.copy(status = ClaimStatus.DEPENDS_ON_INPUT, statusReason = "Depends on your situation or your choice")
        val verified = c.support.filter { !it.pointerOnly }
        val opposing = c.against.filter { !it.pointerOnly }
        val pointers = c.support.filter { it.pointerOnly }
        val origins = verified.map { it.originKey }.distinct().size
        return when {
            verified.isNotEmpty() && opposing.isNotEmpty() -> c.copy(status = ClaimStatus.CONTESTED, statusReason = "Sources disagree about this")
            verified.isNotEmpty() && c.volatile && verified.all { nowMs - it.retrievedAtMs > VOLATILE_FRESH_FOR.toMillis() } ->
                c.copy(status = ClaimStatus.INDICATIVE, statusReason = "Changes quickly; the evidence is ${Duration.ofMillis(nowMs - verified.maxOf { it.retrievedAtMs }).toDays()} days old")
            verified.isNotEmpty() -> c.copy(status = ClaimStatus.ESTABLISHED, statusReason = if (origins == 1) "One source, in scope" else "$origins independent sources, in scope")
            pointers.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = "Restated from earlier evidence, not checked here")
            opposing.isNotEmpty() -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "No support found; the sources found point the other way")
            else -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "No source supports this")
        }
    }

    /** A judgment or recommendation inherits the weakest premise (RES-041); it is never established by sources itself. */
    private fun derived(c: Claim, all: List<Claim>): Claim {
        if (!c.checked) return c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "Not checked before the run stopped")
        val premises = c.premises.mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (premises.isEmpty()) return c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "${c.type.label.replaceFirstChar { it.uppercase() }} with no established premise")
        val weakest = premises.minByOrNull { rank(it.status) }!!
        val reason = when (weakest.status) {
            ClaimStatus.ESTABLISHED -> "${c.type.label.replaceFirstChar { it.uppercase() }}; its premises are established"
            ClaimStatus.DEPENDS_ON_INPUT -> "Depends on: ${weakest.text.take(80)}"
            else -> "Rests on something ${label(weakest.status)}: ${weakest.text.take(80)}"
        }
        return c.copy(status = weakest.status, statusReason = reason)
    }

    private fun rank(s: ClaimStatus): Int = when (s) {
        ClaimStatus.NOT_ESTABLISHED -> 0
        ClaimStatus.CONTESTED -> 1
        ClaimStatus.INDICATIVE -> 2
        ClaimStatus.DEPENDS_ON_INPUT -> 3
        ClaimStatus.ESTABLISHED -> 4
    }

    /** Plain words for a status; the enum name never reaches the user (RES-087, RES-105). */
    fun label(s: ClaimStatus): String = when (s) {
        ClaimStatus.ESTABLISHED -> "well supported"
        ClaimStatus.INDICATIVE -> "only indicated"
        ClaimStatus.CONTESTED -> "disputed between sources"
        ClaimStatus.NOT_ESTABLISHED -> "not established"
        ClaimStatus.DEPENDS_ON_INPUT -> "up to you"
    }

    /** Short badge text for evidence rows. */
    fun badge(s: ClaimStatus): String = when (s) {
        ClaimStatus.ESTABLISHED -> "Supported"
        ClaimStatus.INDICATIVE -> "Indicated"
        ClaimStatus.CONTESTED -> "Disputed"
        ClaimStatus.NOT_ESTABLISHED -> "Not established"
        ClaimStatus.DEPENDS_ON_INPUT -> "Depends on you"
    }
}
