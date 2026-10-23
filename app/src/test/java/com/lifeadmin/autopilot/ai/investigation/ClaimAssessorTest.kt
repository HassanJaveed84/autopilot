package com.lifeadmin.autopilot.ai.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Document F Stage 0: a fixed evidence set maps to fixed statuses (RES-042, 045, 046, 064, 070). */
class ClaimAssessorTest {
    private val now = 1_800_000_000_000L
    private fun ev(n: Int, origin: String = "site$n.example", at: Long = now, pointer: Boolean = false) = EvidenceRef(n, "excerpt $n", retrievedAtMs = at, originKey = origin, pointerOnly = pointer)
    private fun claim(id: String, type: ClaimType = ClaimType.FACT, support: List<EvidenceRef> = emptyList(), against: List<EvidenceRef> = emptyList(), premises: List<String> = emptyList(), volatile: Boolean = false, dependsOnUser: Boolean = false, checked: Boolean = true) =
        Claim(id, "claim $id", type, loadBearing = true, volatile = volatile, dependsOnUser = dependsOnUser, support = support, against = against, premises = premises, turnId = "t1", checked = checked)

    @Test fun fixedEvidenceSetsMapToTheExpectedStatuses() {
        val claims = listOf(
            claim("c1", support = listOf(ev(1))),
            claim("c2"),
            claim("c3", support = listOf(ev(1, pointer = true))),
            claim("c4", support = listOf(ev(1)), against = listOf(ev(2))),
            claim("c5", dependsOnUser = true),
            claim("c6", checked = false, support = listOf(ev(1))),
        )
        val out = ClaimAssessor.assess(claims, now).associateBy { it.id }
        assertEquals(ClaimStatus.ESTABLISHED, out["c1"]!!.status)
        assertEquals(ClaimStatus.NOT_ESTABLISHED, out["c2"]!!.status)
        assertEquals("a model-typed pointer never verifies", ClaimStatus.INDICATIVE, out["c3"]!!.status)
        assertEquals("one claim, two positions", ClaimStatus.CONTESTED, out["c4"]!!.status)
        assertEquals(ClaimStatus.DEPENDS_ON_INPUT, out["c5"]!!.status)
        assertEquals("not checked before the stop is never established, whatever evidence it carried", ClaimStatus.NOT_ESTABLISHED, out["c6"]!!.status)
    }

    @Test fun twoPagesFromOneOriginCountAsOneSource() {
        val one = ClaimAssessor.assessOne(claim("c1", support = listOf(ev(1, "labreview.example"), ev(2, "labreview.example"))), now)
        val two = ClaimAssessor.assessOne(claim("c2", support = listOf(ev(1, "a.example"), ev(2, "b.example"))), now)
        assertEquals(1, one.independentOrigins)
        assertEquals(2, two.independentOrigins)
        assertTrue(one.statusReason.startsWith("One source"))
        assertTrue(two.statusReason.startsWith("2 independent sources"))
    }

    @Test fun judgmentsInheritTheWeakestPremiseAndAreNeverEstablishedOnTheirOwn() {
        val claims = listOf(
            claim("c1", support = listOf(ev(1))),
            claim("c2"),
            claim("r1", type = ClaimType.RECOMMENDATION, premises = listOf("c1", "c2")),
            claim("j1", type = ClaimType.JUDGMENT, premises = listOf("c1")),
            claim("j2", type = ClaimType.JUDGMENT, support = listOf(ev(3))),
        )
        val out = ClaimAssessor.assess(claims, now).associateBy { it.id }
        assertEquals(ClaimStatus.NOT_ESTABLISHED, out["r1"]!!.status)
        assertEquals("all premises established → inherits established", ClaimStatus.ESTABLISHED, out["j1"]!!.status)
        assertEquals("a cited judgment with no premises is still not established by its citation", ClaimStatus.NOT_ESTABLISHED, out["j2"]!!.status)
    }

    @Test fun volatileClaimsAreJudgedByTheFactsOwnDateNotTheFetchTime() {
        val day = 24L * 3600 * 1000
        val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        fun e(n: Int, asOf: String?, at: Long = now) = ev(n, at = at).copy(asOf = asOf)
        val fetchedLongAgo = ClaimAssessor.assessOne(claim("c1", volatile = true, support = listOf(e(1, today.minusDays(8).toString(), at = now - 8 * day))), now)
        val datedToday = ClaimAssessor.assessOne(claim("c2", volatile = true, support = listOf(e(1, today.toString()))), now)
        val oldFactFetchedNow = ClaimAssessor.assessOne(claim("c3", volatile = true, support = listOf(e(1, today.minusDays(40).toString()))), now)
        val undated = ClaimAssessor.assessOne(claim("c4", volatile = true, support = listOf(e(1, null))), now)
        val stable = ClaimAssessor.assessOne(claim("c5", volatile = false, support = listOf(e(1, "2019", at = now - 8 * day))), now)
        assertEquals(ClaimStatus.INDICATIVE, fetchedLongAgo.status)
        assertTrue(fetchedLongAgo.statusReason, fetchedLongAgo.statusReason.contains("8 days old"))
        assertEquals(ClaimStatus.ESTABLISHED, datedToday.status)
        assertEquals("fetched today is not the same as true today", ClaimStatus.INDICATIVE, oldFactFetchedNow.status)
        assertTrue(oldFactFetchedNow.statusReason, oldFactFetchedNow.statusReason.startsWith("As of"))
        assertEquals("a fast-changing fact with no date cannot be called current", ClaimStatus.INDICATIVE, undated.status)
        assertEquals("a stable fact is not stale because its page is old", ClaimStatus.ESTABLISHED, stable.status)
    }

    @Test fun sameEvidenceSameStatusWhateverTheRunConfiguration() {
        // The assessor takes no model or depth: the only way for configuration to change a status is to change the evidence.
        val claims = listOf(claim("c1", support = listOf(ev(1))), claim("c2"), claim("c3", support = listOf(ev(1)), against = listOf(ev(2))))
        val a = ClaimAssessor.assess(claims.map { it.copy(turnId = "quick-haiku") }, now).map { it.status }
        val b = ClaimAssessor.assess(claims.map { it.copy(turnId = "deep-opus") }, now).map { it.status }
        assertEquals(a, b)
    }

    @Test fun statusWordsForPeopleNeverLeakTheEnumName() {
        ClaimStatus.entries.forEach { s ->
            assertTrue(LeakageScan.scan(ClaimAssessor.label(s)).isEmpty())
            assertTrue(LeakageScan.scan(ClaimAssessor.badge(s)).isEmpty())
        }
    }
}
