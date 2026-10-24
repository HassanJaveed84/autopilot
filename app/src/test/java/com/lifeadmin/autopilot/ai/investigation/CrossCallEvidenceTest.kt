package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.context.TurnPayload
import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The general rules behind the R-N(a) fixes, exercised in other domains (`docs/live-eval/R-N-a-2026-10-23.md`):
 * freshness of undated stable facts, evidence kept across calls of one turn, a later call's narration about its
 * own search, and the held evidence the gap-filling prompt names.
 */
class CrossCallEvidenceTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day = 24L * 3600 * 1000

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private class Turn(val work: TurnWork, val registry: SourceRegistry)

    private fun turn(inherited: List<Claim> = emptyList()) = Turn(TurnWork("t2", inherited, taskLinked = false), SourceRegistry(turnId = "t2"))

    private fun Turn.call(vararg reply: RawSegment) {
        val known = work.current()
        work.merge(InvestigationParser.parse(reply.toList(), emptyList(), registry, "t2", now, known, InvestigationParser.evidenceLines(known)), now)
    }

    private fun Turn.result(question: String): TurnResult {
        work.verifyRan = true
        return work.result(ObjectivePreparer.local(question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    // ------------------------------------------------------------------ freshness of an undated stable fact

    @Test fun anUndatedStableFactIsCurrentWhileItWasReadWithinItsWindow() {
        fun claim(volatile: Boolean, readDaysAgo: Long) = Claim(
            "c1", "The museum is at 1 Example Square.", ClaimType.FACT, loadBearing = true, volatile = volatile, freshForDays = 365,
            support = listOf(EvidenceRef(1, "Address: 1 Example Square.", kind = EvidenceKind.PRIMARY, retrievedAtMs = now - readDaysAgo * day, originKey = "museum.example")), turnId = "t1",
        )
        assertEquals(ClaimStatus.ESTABLISHED, ClaimAssessor.assessOne(claim(volatile = false, readDaysAgo = 0), now).status)
        val old = ClaimAssessor.assessOne(claim(volatile = false, readDaysAgo = 400), now)
        assertEquals("read longer ago than its own window, an undated page no longer settles it", ClaimStatus.INDICATIVE, old.status)
        assertTrue(old.statusReason, old.statusReason.contains("last read 400 days ago"))
        assertEquals("a volatile fact still needs the page's own date", ClaimStatus.INDICATIVE, ClaimAssessor.assessOne(claim(volatile = true, readDaysAgo = 0), now).status)
    }

    // ------------------------------------------------------------------ evidence across the calls of one turn

    @Test fun aLaterCallThatRestatesAClaimKeepsTheEvidenceTheTurnAlreadyHeld() {
        val t = turn()
        t.call(
            s("## CLAIM c1 · type=fact · load=yes · scope=City pool, winter hours · asof=- · volatile=no · fresh=1y · needs=corroborated · needs_input=no\nThe city pool opens at 07:00 in winter.\nEVIDENCE: (kind=secondary · origin=Local News) "),
            s("Winter opening 07:00.", cite("https://www.localnews.example/pool", "Winter opening 07:00.")),
        )
        t.call(
            s("## CLAIM c1 · type=fact · load=yes · scope=City pool, winter hours · asof=- · volatile=no · fresh=1y · needs=corroborated · needs_input=no\nThe city pool opens at 07:00 in winter.\nEVIDENCE: (kind=secondary · origin=Visit City) "),
            s("Pool: winter 7am.", cite("https://www.visitcity.example/pool", "Pool: winter 7am.")),
        )
        val c1 = t.work.current().first { it.id == "c1" }
        assertEquals("both calls' sources count", setOf("localnews.example", "visitcity.example"), c1.support.map { it.originKey }.toSet())
        assertEquals("two origins known to be independent meet the corroboration it asked for", ClaimStatus.ESTABLISHED, c1.status)
    }

    @Test fun aChangedValueIsANewStatementAndDoesNotInheritTheEarlierEvidence() {
        val t = turn()
        t.call(
            s("## CLAIM c1 · type=fact · load=yes · scope=Day pass, City pool · asof=2026-10 · volatile=yes · needs_input=no\nA day pass costs $6.\nEVIDENCE: (kind=secondary · asof=2026-09) "),
            s("Day pass $6.", cite("https://www.blog.example/pool-prices", "Day pass $6.")),
        )
        t.call(
            s("## CLAIM c1 · type=fact · load=yes · scope=Day pass, City pool · asof=2026-10 · volatile=yes · needs_input=no\nA day pass costs $8.\nEVIDENCE: (kind=primary · asof=2026-10) "),
            s("Day pass: $8 from 1 October 2026.", cite("https://www.citypool.example/prices", "Day pass: $8 from 1 October 2026.")),
        )
        val c1 = t.work.current().first { it.id == "c1" }
        assertEquals(listOf("citypool.example"), c1.support.map { it.originKey })
    }

    @Test fun aReCheckOfAFindingFromAnEarlierTurnStillStandsAsWritten() {
        val inherited = Claim(
            "c1", "The permit fee is $40.", ClaimType.FACT, loadBearing = true, volatile = true, asOf = "2026-01",
            support = listOf(EvidenceRef(1, "Fee $40.", asOf = "2026-01", kind = EvidenceKind.PRIMARY, retrievedAtMs = now - 200 * day, originKey = "council.example")), turnId = "t1",
        )
        val t = turn(listOf(inherited))
        t.call(
            s("## CLAIM c1 · type=fact · load=yes · scope=Parking permit · asof=2026-10 · volatile=yes · needs_input=no\nThe permit fee is $40.\nEVIDENCE: (kind=primary · asof=2026-10) "),
            s("Permit fee: $40 (October 2026).", cite("https://www.council.example/permits", "Permit fee: $40 (October 2026).")),
        )
        val c1 = t.work.current().first { it.id == "c1" }
        assertEquals("RES-106: the earlier turn's evidence is not carried into the fresh re-check", 1, c1.support.size)
        assertEquals("2026-10", c1.support.single().asOf)
    }

    // ------------------------------------------------------------------ a later call's narration about its own search

    @Test fun aLaterCallsSentenceAboutItsOwnSearchIsLeftOutOfTheAnswer() {
        val t = turn()
        t.call(
            s("## ANSWER\nThe form is SA-1 [c1]. I found no 2026 version in this search.\n## CLAIM c1 · type=fact · load=yes · scope=renewal form · asof=- · volatile=no · needs=direct · needs_input=no\nThe renewal form is SA-1.\nEVIDENCE: (kind=primary) "),
            s("Use form SA-1 to renew.", cite("https://www.agency.example/renew", "Use form SA-1 to renew.")),
        )
        assertTrue("the first call speaks for the whole turn so far; its limitation stays", t.work.answer.joinToString(" ") { it.text }.contains("in this search"))
        t.call(
            s("## ANSWER\nThe form is SA-1 [c1]. The agency's own page was not retrieved in this search, so it is not cited here. A guide site agrees [c1].\n## CLAIM c1 · type=fact · load=yes · scope=renewal form · asof=- · volatile=no · needs=direct · needs_input=no\nThe renewal form is SA-1.\nEVIDENCE: (kind=secondary) "),
            s("Renew with SA-1.", cite("https://www.guide.example/renew", "Renew with SA-1.")),
            s("\n## UNRESOLVED\n- Agency wording — the page is already held as s1 but was not in these results.\n- 2026 edition — searched the agency site and found no 2026 edition."),
        )
        val r = t.result("Which form renews it?")
        val lead = r.answer.lead.joinToString(" ") { it.text }
        assertFalse(lead, lead.contains("not retrieved"))
        assertTrue(lead, lead.contains("A guide site agrees"))
        assertTrue("the agency's page gathered first still supports the claim", r.claim("c1")!!.support.any { it.originKey == "agency.example" })
        val unresolved = r.answer.sections.filter { it.kind == SectionKind.UNRESOLVED }.flatMap { it.items }.map { it.text }
        assertEquals("a note about re-citing a held page is dropped; a real open question is kept", listOf("2026 edition — searched the agency site and found no 2026 edition."), unresolved)
    }

    // ------------------------------------------------------------------ the gap-filling prompt names what is held and why the claim is open

    @Test fun theGapFillingPromptListsHeldEvidenceAndTheReasonEachClaimIsStillOpen() {
        val open = Claim(
            "c1", "The ferry runs hourly on Sundays.", ClaimType.FACT, loadBearing = true, volatile = true, status = ClaimStatus.INDICATIVE,
            statusReason = "This changes within about 7 days, and no source dates it", scope = "Island ferry, Sunday timetable",
            support = listOf(EvidenceRef(3, "Sundays: hourly.", kind = EvidenceKind.PRIMARY, retrievedAtMs = now, originKey = "ferry.example")), turnId = "t2",
        )
        val source = com.lifeadmin.autopilot.ai.research2.SourceRef2(3, "https://www.ferry.example/timetable", "Timetable", "www.ferry.example", false, citedCount = 1)
        val p = InvestigationPrompt.verify(TurnPayload(objective = "Find out the Sunday ferry frequency", config = com.lifeadmin.autopilot.ai.context.TurnConfig(model = "m", depth = "standard", searchBudget = 3)), listOf(open), listOf(source), emptyList(), 1, 2)
        assertTrue(p, p.contains("(This changes within about 7 days, and no source dates it)"))
        assertTrue(p, p.contains("evidence already held for it: s3 (primary, undated)"))
        assertTrue(p, p.contains("Never write that a held source was not retrieved"))
    }
}
