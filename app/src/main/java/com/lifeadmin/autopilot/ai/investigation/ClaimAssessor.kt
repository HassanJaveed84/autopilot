package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.Sources
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
        val groups = origins(usable)
        return when {
            usable.isNotEmpty() && opposing.isNotEmpty() -> c.copy(status = ClaimStatus.CONTESTED, statusReason = "Sources disagree about this")
            usable.isNotEmpty() && meets(standard, usable, groups) -> c.copy(status = ClaimStatus.ESTABLISHED, statusReason = established(standard, usable, groups))
            usable.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = "This needs ${standard.label}; what was found falls short (${describe(usable)}${independenceNote(standard, usable, groups)})")
            inScope.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = staleReason(c, inScope, nowMs))
            pointers.isNotEmpty() -> c.copy(status = ClaimStatus.INDICATIVE, statusReason = "Restated from earlier evidence, not checked here")
            // The note is the reply's own label for what the page covers; the reason reports it as a label, never as a verified mismatch.
            offScope.isNotEmpty() -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "Its only ${if (offScope.size == 1) "source was" else "sources were"} marked as being about something else (${offScope.first().scopeNote.removePrefix("About ").trimEnd('.')}), so ${if (offScope.size == 1) "it does" else "they do"} not count for ${c.scope.ifBlank { "this claim" }}")
            c.against.any { !it.pointerOnly } -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "No support found; the sources found point the other way")
            else -> c.copy(status = ClaimStatus.NOT_ESTABLISHED, statusReason = "No source supports this")
        }
    }

    /**
     * RES-043: proportional, never a universal count. Independent agreement is one route among three, not a minimum,
     * and it counts only origins whose independence is known (RES-045): see [origins].
     */
    private fun meets(standard: EvidenceStandard, usable: List<EvidenceRef>, groups: List<Origin>): Boolean = when (standard) {
        EvidenceStandard.DIRECT -> true
        EvidenceStandard.CORROBORATED -> issuerOrReview(usable) || groups.count { it.attributed } >= 2
        EvidenceStandard.REVIEW -> usable.any { it.kind == EvidenceKind.REVIEW }
    }

    private fun issuerOrReview(usable: List<EvidenceRef>) = usable.any { it.kind == EvidenceKind.PRIMARY || it.kind == EvidenceKind.REVIEW }

    private fun established(standard: EvidenceStandard, usable: List<EvidenceRef>, groups: List<Origin>): String = when {
        standard == EvidenceStandard.REVIEW -> "Review-level evidence, in scope"
        usable.any { it.kind == EvidenceKind.PRIMARY } && groups.size == 1 -> "The issuing source, in scope"
        groups.size == 1 -> "One source, in scope"
        standard == EvidenceStandard.CORROBORATED && !issuerOrReview(usable) -> "${groups.count { it.attributed }} independent sources agree, in scope"
        // Without known independence the reason claims only what was checked: separate origins.
        else -> "${groups.size} sources from separate origins, in scope"
    }

    private fun independenceNote(standard: EvidenceStandard, usable: List<EvidenceRef>, groups: List<Origin>): String = when {
        standard != EvidenceStandard.CORROBORATED || issuerOrReview(usable) -> ""
        groups.size == 1 && usable.size > 1 -> ", all from one origin"
        groups.size >= 2 -> "; it is not known whether they are independent of each other"
        else -> ""
    }

    /** One origin of evidence: pages that share a site, a stated publisher or a passage of text. */
    class Origin(val members: List<EvidenceRef>) {
        /** The reply said who first published at least one of its pages; only such origins can count as independent. */
        val attributed: Boolean get() = members.any { it.origin != null }
    }

    /**
     * RES-045: groups evidence into origins. Two pages are one origin when they are on one registrable site
     * (subdomains included), when the reply names the same publisher for both, when the publisher it names for one
     * is the other's site, or when their cited text shares a passage of [SHARED_WORDS] words or more (one
     * announcement or data release repeated). These checks only merge: a reply calling two pages independent never
     * separates pages they join, and a page whose publisher the reply does not state has independence unknown.
     */
    fun origins(evidence: List<EvidenceRef>): List<Origin> {
        val parent = IntArray(evidence.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in evidence.indices) for (j in i + 1 until evidence.size) if (sameOrigin(evidence[i], evidence[j])) parent[find(i)] = find(j)
        return evidence.indices.groupBy { find(it) }.values.map { ix -> Origin(ix.map { evidence[it] }) }
    }

    const val SHARED_WORDS = 12

    private fun sameOrigin(a: EvidenceRef, b: EvidenceRef): Boolean {
        if (Sources.site(a.originKey) == Sources.site(b.originKey)) return true
        val na = a.origin?.let(::words)
        val nb = b.origin?.let(::words)
        if (na != null && nb != null && na.joinToString("") == nb.joinToString("")) return true
        if (na != null && names(na, Sources.siteName(b.originKey))) return true
        if (nb != null && names(nb, Sources.siteName(a.originKey))) return true
        return sharesPassage(a.quote, b.quote)
    }

    /** A stated publisher names a site when its words are the site's name, or one of them is ("BBC News" names bbc.co.uk). */
    private fun names(publisher: List<String>, siteName: String): Boolean = siteName.length >= 3 && (publisher.joinToString("") == siteName || siteName in publisher)

    private fun sharesPassage(a: String?, b: String?): Boolean {
        val wa = a?.let(::words)?.takeIf { it.size >= SHARED_WORDS } ?: return false
        val wb = b?.let(::words)?.takeIf { it.size >= SHARED_WORDS } ?: return false
        val runs = wa.windowed(SHARED_WORDS).map { it.joinToString(" ") }.toSet()
        return wb.windowed(SHARED_WORDS).any { it.joinToString(" ") in runs }
    }

    private fun words(s: String): List<String> = s.lowercase(java.util.Locale.ROOT).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

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
     *
     * Undated evidence for a stable (non-volatile) claim is current while it was read within the window: a
     * page stating a fact that does not change is evidence it holds when read. Only a volatile claim needs
     * the fact's own date, because there an undated page may report a value that has since moved.
     */
    fun fresh(c: Claim, e: EvidenceRef, nowMs: Long): Boolean {
        val window = window(c) ?: return true
        val factEnd = endOfPeriodMs(e.asOf ?: c.asOf) ?: return !c.volatile && nowMs - e.retrievedAtMs <= window.toMillis()
        return nowMs - factEnd <= window.toMillis() && nowMs - e.retrievedAtMs <= window.toMillis()
    }

    private fun window(c: Claim): Duration? = c.freshForDays?.let { Duration.ofDays(it.toLong()) } ?: if (c.volatile) VOLATILE_FRESH_FOR else null

    private fun staleReason(c: Claim, inScope: List<EvidenceRef>, nowMs: Long): String {
        val dated = inScope.mapNotNull { it.asOf ?: c.asOf }.maxByOrNull { endOfPeriodMs(it) ?: Long.MIN_VALUE }
        val days = window(c)?.toDays() ?: 0
        val readDaysAgo = Duration.ofMillis(nowMs - inScope.maxOf { it.retrievedAtMs }).toDays()
        return if ((dated == null || endOfPeriodMs(dated) == null) && !c.volatile) "No source dates it, and it was last read $readDaysAgo days ago; it needs re-checking every $days days or so"
        else if (dated == null || endOfPeriodMs(dated) == null) "This changes within about $days days, and no source dates it"
        else {
            val fetchedDaysAgo = readDaysAgo
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
