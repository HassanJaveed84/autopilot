package com.lifeadmin.autopilot.ai.investigation

import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Derives every claim's status from the evidence attached to that claim (RES-042–046, 070).
 * Pure and configuration-free: the same evidence yields the same statuses whatever model or
 * depth gathered it (RES-064). The model's own status words are never an input.
 */
object ClaimAssessor {
    /** Default for a claim marked volatile that states no window of its own (RES-046: per claim, not a category default). */
    val VOLATILE_FRESH_FOR: Duration = Duration.ofDays(7)

    /**
     * @param consequential the objective is consequential (RES-043/053): a load-bearing claim that states no
     *   standard then needs the issuer, a review or independent confirmation, never one secondary source.
     */
    fun assess(claims: List<Claim>, nowMs: Long, consequential: Boolean = false): List<Claim> {
        // Premise statuses first, then derived claims that rest on them; two passes settle chains of judgments.
        var current = claims.map { if (it.type.derived) it else assessOne(it, nowMs, consequential) }
        repeat(3) { current = current.map { if (it.type.derived) derived(it, current) else it } }
        return current
    }

    fun assessOne(c: Claim, nowMs: Long, consequential: Boolean = false): Claim {
        if (c.superseded) return c.copy(statusReason = c.statusReason.ifBlank { "Replaced by a later finding" })
        if (!c.checked) return c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "Not checked before the run stopped")
        if (c.dependsOnUser) return c.copy(status = ClaimStatus.DEPENDS_ON_INPUT, statusReason = "Depends on your situation or your choice")
        val verified = c.support.filter { !it.pointerOnly }
        val pointers = c.support.filter { it.pointerOnly }
        // RES-044: a source about something else supports nothing; RES-046: neither does one too old for this claim.
        val inScope = verified.filter { it.scopeNote.isBlank() }
        val offScope = verified - inScope.toSet()
        val usable = inScope.filter { fresh(c, it, nowMs) }
        // RES-071: opposing evidence about another scope or an earlier period is a different claim or a change over time, not a conflict.
        val opposing = c.against.filter { !it.pointerOnly && it.scopeNote.isBlank() && fresh(c, it, nowMs) }
        val standard = c.needs ?: if (consequential && c.loadBearing) EvidenceStandard.CORROBORATED else EvidenceStandard.DIRECT
        val origins = usable.map { it.originKey }.distinct().size
        return when {
            usable.isNotEmpty() && opposing.isNotEmpty() -> c.copy(status = ClaimStatus.CONTESTED, statusReason = "Sources disagree about this")
            usable.isNotEmpty() && meets(standard, usable) -> c.copy(status = ClaimStatus.ESTABLISHED, statusReason = established(standard, usable, origins))
            usable.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = "This needs ${standard.label}; what was found falls short (${describe(usable)})")
            inScope.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = staleReason(c, inScope, nowMs))
            pointers.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = "Restated from earlier evidence, not checked here")
            offScope.isNotEmpty() -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "The source found is about ${offScope.first().scopeNote.removePrefix("About ").trimEnd('.')}, not ${c.scope.ifBlank { "this claim" }}")
            c.against.any { !it.pointerOnly } -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "No support found; the sources found point the other way")
            else -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "No source supports this")
        }
    }

    /** RES-043: proportional, never a universal count. Independent agreement is one route among three, not a minimum. */
    private fun meets(standard: EvidenceStandard, usable: List<EvidenceRef>): Boolean = when (standard) {
        EvidenceStandard.DIRECT -> true
        EvidenceStandard.CORROBORATED -> usable.any { it.kind == EvidenceKind.PRIMARY || it.kind == EvidenceKind.REVIEW } || usable.map { it.originKey }.distinct().size >= 2
        EvidenceStandard.REVIEW -> usable.any { it.kind == EvidenceKind.REVIEW }
    }

    private fun established(standard: EvidenceStandard, usable: List<EvidenceRef>, origins: Int): String = when {
        standard == EvidenceStandard.REVIEW -> "Review-level evidence, in scope"
        usable.any { it.kind == EvidenceKind.PRIMARY } && origins == 1 -> "The issuing source, in scope"
        origins == 1 -> "One source, in scope"
        else -> "$origins independent sources, in scope"
    }

    /** Plain words for what was found, e.g. "one study and one secondary source". */
    private fun describe(usable: List<EvidenceRef>): String {
        fun word(k: EvidenceKind?, n: Int): String = when (k) {
            EvidenceKind.PRIMARY -> if (n == 1) "one issuing source" else "$n issuing sources"
            EvidenceKind.REVIEW -> if (n == 1) "one review" else "$n reviews"
            EvidenceKind.STUDY -> if (n == 1) "one study" else "$n studies"
            EvidenceKind.SECONDARY -> if (n == 1) "one secondary source" else "$n secondary sources"
            null -> if (n == 1) "one source of unstated standing" else "$n sources of unstated standing"
        }
        val parts = usable.groupingBy { it.kind }.eachCount().map { (k, n) -> word(k, n) }
        return if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /**
     * RES-046. The age that counts is the fact's own date (the evidence's, else the claim's), never the
     * fetch time alone. The window is the claim's own; a volatile claim without one gets [VOLATILE_FRESH_FOR];
     * a stable fact without one stays current unless its evidence says otherwise.
     */
    fun fresh(c: Claim, e: EvidenceRef, nowMs: Long): Boolean {
        val window = window(c) ?: return true
        val factEnd = endOfPeriodMs(e.asOf ?: c.asOf) ?: return false
        return nowMs - factEnd <= window.toMillis() && nowMs - e.retrievedAtMs <= window.toMillis()
    }

    private fun window(c: Claim): Duration? = c.freshForDays?.let { Duration.ofDays(it.toLong()) } ?: if (c.volatile) VOLATILE_FRESH_FOR else null

    private fun staleReason(c: Claim, inScope: List<EvidenceRef>, nowMs: Long): String {
        val dated = inScope.mapNotNull { it.asOf ?: c.asOf }.maxByOrNull { endOfPeriodMs(it) ?: Long.MIN_VALUE }
        val days = window(c)?.toDays() ?: 0
        return if (dated == null || endOfPeriodMs(dated) == null) "This changes within about $days days, and no source dates it"
        else {
            val fetchedDaysAgo = Duration.ofMillis(nowMs - inScope.maxOf { it.retrievedAtMs }).toDays()
            if (fetchedDaysAgo > days) "Changes within about $days days; the evidence is $fetchedDaysAgo days old"
            else "As of $dated; this changes within about $days days, so it may no longer be current"
        }
    }

    /** End of the stated period in UTC ms: "2026-10-22", "2026-10" (end of October), "2026" (end of year). Anything else is undated. */
    fun endOfPeriodMs(asOf: String?): Long? {
        val t = asOf?.trim() ?: return null
        val date = runCatching {
            when {
                Regex("""\d{4}-\d{2}-\d{2}""").matches(t) -> LocalDate.parse(t)
                Regex("""\d{4}-\d{2}""").matches(t) -> YearMonth.parse(t).atEndOfMonth()
                Regex("""\d{4}""").matches(t) -> LocalDate.of(t.toInt(), 12, 31)
                else -> null
            }
        }.getOrNull() ?: return null
        return date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1
    }

    /** A judgment or recommendation inherits the weakest premise (RES-041); it is never established by sources itself. */
    private fun derived(c: Claim, all: List<Claim>): Claim {
        if (!c.checked) return c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "Not checked before the run stopped")
        val premises = c.premises.mapNotNull { id -> all.firstOrNull { it.id == id } }
        // RES-107: reasoning from a finding that was replaced is not current, whatever the replacement says.
        premises.firstOrNull { it.superseded }?.let { old -> return c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "Rests on a finding that was replaced: ${old.text.take(80)}") }
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
