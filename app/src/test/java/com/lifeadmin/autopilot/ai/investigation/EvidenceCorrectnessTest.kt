package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * RES-042–046, 070–072, 107 through the real parse → merge → assess → derive → compose path, with
 * replies written in the objective-led contract. Each case is one evidence situation; nothing here
 * is a domain template, and no case is decided by counting sources alone.
 */
class EvidenceCorrectnessTest {
    private val now = LocalDate.of(2026, 10, 23).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun run(question: String, vararg reply: RawSegment, consequential: Boolean? = null, taskLinked: Boolean = false): TurnResult {
        val objective = ObjectivePreparer.local(question).let { if (consequential != null) it.copy(consequential = consequential) else it }
        val registry = SourceRegistry(turnId = "t1")
        val out = InvestigationParser.parse(reply.toList(), emptyList(), registry, "t1", now)
        val work = TurnWork("t1", emptyList(), taskLinked, consequential = objective.consequential)
        work.merge(out, now)
        work.verifyRan = true
        return work.result(objective, TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    // ------------------------------------------------------------------ RES-043: proportional sufficiency

    @Test fun aNarrowFactIsEstablishedByOneDirectlyAuthoritativeSource() {
        val r = run(
            "When does the Lisbon passport office open on Saturdays?",
            s("## ANSWER\nIt opens at 9:00 on Saturdays [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=Lisbon passport office, Saturday hours · asof=2026-10 · volatile=no · needs_input=no\nThe Lisbon passport office opens at 9:00 on Saturdays.\nEVIDENCE: (kind=primary) "),
            s("Saturday: 09:00–13:00.", cite("https://www.passport-office.example/lisbon", "Saturday: 09:00–13:00.")),
        )
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertEquals("one authoritative source answers it; no second source is demanded", ResultState.ANSWERED, r.state)
    }

    @Test fun aNarrowFactInAConsequentialQuestionStillNeedsOnlyItsAuthoritativeSource() {
        val r = run(
            "What is the maximum daily dose printed on the label of Brand X ibuprofen 200 mg?",
            s("## ANSWER\nThe label says no more than six tablets in 24 hours [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=Brand X ibuprofen 200 mg, US label · asof=2026 · volatile=no · needs_input=no\nThe Brand X 200 mg label allows at most six tablets in 24 hours.\nEVIDENCE: (kind=primary) "),
            s("Do not take more than 6 tablets in 24 hours.", cite("https://www.brandx.example/ibuprofen-200/label", "Do not take more than 6 tablets in 24 hours.")),
            consequential = true,
        )
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
    }

    @Test fun aConsequentialBenefitClaimOnLowQualityEvidenceIsOnlyIndicative() {
        val r = run(
            "Does magnesium glycinate improve sleep in adults?",
            s("## ANSWER\nSome sources say it helps adults fall asleep faster [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=adults, magnesium glycinate, sleep onset · asof=2026 · volatile=no · needs_input=no · needs=review\nMagnesium glycinate shortens sleep onset in adults.\nEVIDENCE: (kind=secondary) "),
            s("Readers report falling asleep faster.", cite("https://www.wellness-blog.example/magnesium", "Readers report falling asleep faster.")),
            s("\nEVIDENCE: (kind=study) "),
            s("In 46 older adults, sleep onset improved by 17 minutes.", cite("https://journal.example/2012/mg-elderly", "In 46 older adults, sleep onset improved by 17 minutes.")),
            consequential = true,
        )
        val c1 = r.claim("c1")!!
        assertEquals("two weak sources do not add up to the review-level evidence this claim needs", ClaimStatus.INDICATIVE, c1.status)
        assertTrue(c1.statusReason, c1.statusReason.contains("review", ignoreCase = true))
        assertTrue(c1.statusReason, c1.statusReason.endsWith("(one secondary source and one study)"))
        assertEquals(ResultState.NOT_ESTABLISHED, r.state)
        assertTrue("the gap is stated in the main answer", r.answer.caveats.any { it.startsWith("Only indicated") })
    }

    @Test fun aConsequentialClaimWithNoDeclaredStandardIsNotEstablishedByOneSecondarySource() {
        val r = run(
            "Does this supplement lower blood pressure?",
            s("## ANSWER\nOne article says it lowers blood pressure [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=adults with hypertension · asof=2026 · volatile=no · needs_input=no\nThe supplement lowers blood pressure.\nEVIDENCE: "),
            s("Our readers saw their numbers drop.", cite("https://www.health-news.example/bp", "Our readers saw their numbers drop.")),
            consequential = true,
        )
        assertEquals(ClaimStatus.INDICATIVE, r.claim("c1")!!.status)
    }

    @Test fun reviewLevelEvidenceEstablishesTheConsequentialBenefitClaim() {
        val r = run(
            "Does magnesium glycinate improve sleep in adults?",
            s("## ANSWER\nA systematic review found a small benefit for sleep onset in older adults [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=older adults, oral magnesium, sleep onset · asof=2021 · volatile=no · needs_input=no · needs=review\nOral magnesium modestly shortens sleep onset in older adults.\nEVIDENCE: (kind=review) "),
            s("Pooled trials showed sleep onset 17 minutes shorter; evidence quality low to moderate.", cite("https://journal.example/2021/mg-sleep-review", "Pooled trials showed sleep onset 17 minutes shorter; evidence quality low to moderate.")),
            consequential = true,
        )
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
    }

    @Test fun evidenceAttributesAreReadButNeverShownAsPageText() {
        val (attrs, excerpt) = EvidenceAttrs.split(" (kind=study · asof=2024-05 · about=adults over 65) Sleep onset improved by 17 minutes.", "adults")
        assertEquals(EvidenceKind.STUDY, attrs.kind)
        assertEquals("2024-05", attrs.asOf)
        assertEquals("About adults over 65", attrs.scopeNote)
        assertEquals("Sleep onset improved by 17 minutes.", excerpt)
        assertEquals("no parenthesis, nothing changes", EvidenceAttrs() to "Plain text.", EvidenceAttrs.split("Plain text.", "x"))
        assertEquals(listOf(7, 21, 90, 365, 30), listOf("7d", "3w", "3m", "1y", "30").map { EvidenceAttrs.days(it) })
        assertEquals(null, EvidenceAttrs.days("-"))
        assertEquals("fresh=, needs= and about= are scrubbed if they ever leak into answer text", emptyList<String>(), LeakageScan.scan(LeakageScan.clean("It holds fresh=7d needs=review about=adults.")))
    }

    // ------------------------------------------------------------------ RES-044: evidence about the actual claim

    @Test fun aSourceAboutAnotherVariantLocationPopulationOrDateDoesNotEstablishTheClaim() {
        fun one(scope: String, about: String): Claim = run(
            "Is this true?",
            s("## ANSWER\nStatement [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=$scope · asof=2026 · volatile=no · needs_input=no\nStatement about $scope.\nEVIDENCE: (about=$about · kind=primary) "),
            s("The page states it for $about.", cite("https://www.issuer.example/${about.hashCode()}", "The page states it for $about.")),
        ).claim("c1")!!
        val variant = one("Bleu de Chanel Eau de Toilette 50 ml", "Bleu de Chanel Parfum 50 ml")
        val place = one("renewal by mail, Portugal", "renewal by mail, Spain")
        val population = one("children under 12", "adults")
        val date = one("2026 filing season", "2023 filing season")
        for (c in listOf(variant, place, population, date)) {
            assertEquals(c.text, ClaimStatus.NOT_ESTABLISHED, c.status)
            assertTrue(c.statusReason, c.statusReason.contains("about", ignoreCase = true))
            assertTrue("the evidence row says what it is actually about", c.support.single().scopeNote.isNotBlank())
        }
    }

    @Test fun evidenceWhoseStatedScopeIsTheClaimsOwnScopeStillCounts() {
        val c = run(
            "Is this true?",
            s("## ANSWER\nStatement [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=Bleu de Chanel EDT 50 ml · asof=2026 · volatile=no · needs_input=no\nBleu de Chanel EDT 50 ml is sold in the US.\nEVIDENCE: (about=bleu de chanel edt 50 ml · kind=primary) "),
            s("Bleu de Chanel EDT 50 ml, ships in the US.", cite("https://www.chanel.example/bleu-edt-50", "Bleu de Chanel EDT 50 ml, ships in the US.")),
        ).claim("c1")!!
        assertEquals(ClaimStatus.ESTABLISHED, c.status)
    }

    // ------------------------------------------------------------------ RES-046: freshness per claim

    @Test fun anOldPriceRetrievedTodayIsNotCurrent() {
        val r = run(
            "How much is Dior Sauvage EDT 100 ml at the department store?",
            s("## ANSWER\nIt was listed at $112 [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=Dior Sauvage EDT 100 ml, US department store · asof=2025-11 · volatile=yes · needs_input=no\nDior Sauvage EDT 100 ml costs $112 at the department store.\nEVIDENCE: (asof=2025-11 · kind=secondary) "),
            s("Black Friday 2025: Sauvage EDT 100 ml $112.", cite("https://www.deals.example/2025/sauvage", "Black Friday 2025: Sauvage EDT 100 ml $112.")),
        )
        val c1 = r.claim("c1")!!
        assertEquals("fetched today, but the price it reports is eleven months old", ClaimStatus.INDICATIVE, c1.status)
        assertTrue(c1.statusReason, c1.statusReason.contains("2025-11"))
        assertTrue(r.answer.caveats.isNotEmpty())
    }

    @Test fun aFastChangingClaimWithNoDateOnItsEvidenceIsNotCurrent() {
        val c1 = run(
            "When does the last train to Sintra leave tonight?",
            s("## ANSWER\nThe last train leaves at 23:41 [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=Lisbon Rossio to Sintra, weekday · asof=- · volatile=yes · needs_input=no\nThe last weekday train to Sintra leaves Rossio at 23:41.\nEVIDENCE: "),
            s("Last departure 23:41.", cite("https://www.timetables.example/sintra", "Last departure 23:41.")),
        ).claim("c1")!!
        assertEquals(ClaimStatus.INDICATIVE, c1.status)
    }

    @Test fun aScheduleDatedWithinItsOwnToleranceIsCurrent() {
        val c1 = run(
            "When does the last train to Sintra leave on weekdays?",
            s("## ANSWER\nThe last weekday train leaves at 23:41 [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=Lisbon Rossio to Sintra, weekday timetable · asof=2026-10-10 · volatile=yes · fresh=60d · needs_input=no\nThe last weekday train to Sintra leaves Rossio at 23:41.\nEVIDENCE: (asof=2026-10-10 · kind=primary) "),
            s("Timetable valid from 10 October 2026: last departure 23:41.", cite("https://www.operator.example/sintra", "Timetable valid from 10 October 2026: last departure 23:41.")),
        ).claim("c1")!!
        assertEquals("a timetable that changes by season is current within its own window", ClaimStatus.ESTABLISHED, c1.status)
    }

    @Test fun anOlderOfficialRuleThatStillAppliesRemainsEstablished() {
        val c1 = run(
            "What documents do I need to renew my passport by mail?",
            s("## ANSWER\nSend your most recent passport and one photo [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=adult renewal by mail · asof=2024-01 · volatile=no · needs_input=no\nAn adult renewing by mail sends the most recent passport and one photo.\nEVIDENCE: (asof=2024-01 · kind=primary) "),
            s("Page last reviewed January 2024: send your most recent passport and one photo.", cite("https://www.passport-office.example/renew", "Page last reviewed January 2024: send your most recent passport and one photo.")),
        ).claim("c1")!!
        assertEquals("a rule page months or years old stays current unless it indicates change", ClaimStatus.ESTABLISHED, c1.status)
    }

    @Test fun aRuleTheEvidenceSaysChangesYearlyIsStaleOnceItsYearHasPassed() {
        val c1 = run(
            "What is the annual contribution limit?",
            s("## ANSWER\nThe limit is 6,500 [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=annual contribution limit, 2026 · asof=2023 · volatile=no · fresh=1y · needs_input=no\nThe annual contribution limit is 6,500.\nEVIDENCE: (asof=2023 · kind=primary) "),
            s("For 2023 the limit is 6,500.", cite("https://www.revenue.example/limits", "For 2023 the limit is 6,500.")),
        ).claim("c1")!!
        assertEquals(ClaimStatus.INDICATIVE, c1.status)
    }

    // ------------------------------------------------------------------ RES-070–072, 107: conflict and history

    @Test fun inScopeConflictingEvidenceMakesOneContestedClaim() {
        val r = run(
            "Is the X9 filter NSF certified?",
            s("## ANSWER\nSources disagree on whether it is certified [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=AquaPure X9 · asof=2026 · volatile=no · needs_input=no\nThe AquaPure X9 is NSF certified.\nEVIDENCE: (kind=primary) "),
            s("X9: NSF/ANSI 53 certified.", cite("https://www.aquapure.example/x9", "X9: NSF/ANSI 53 certified.")),
            s("\nAGAINST: (kind=primary) "),
            s("No current listing for AquaPure X9.", cite("https://www.nsf.example/listings", "No current listing for AquaPure X9.")),
        )
        assertEquals(ClaimStatus.CONTESTED, r.claim("c1")!!.status)
        assertEquals(ResultState.NOT_ESTABLISHED, r.state)
    }

    @Test fun opposingEvidenceAboutAnotherScopeOrAnEarlierPeriodIsNotAConflict() {
        val r = run(
            "How much is the X9 cartridge at retailer A?",
            s("## ANSWER\nIt costs $149 at retailer A [c1].\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=X9 cartridge, retailer A · asof=2026-10-22 · volatile=yes · needs_input=no\nThe X9 cartridge costs $149 at retailer A.\nEVIDENCE: (asof=2026-10-22 · kind=primary) "),
            s("X9 cartridge $149.", cite("https://www.retailer-a.example/x9", "X9 cartridge $149 (price on 22 October 2026).")),
            s("\nAGAINST: (asof=2026-03 · kind=secondary) "),
            s("March deal: X9 cartridge $129 at retailer A.", cite("https://www.deals.example/x9-march", "March deal: X9 cartridge $129 at retailer A.")),
            s("\nAGAINST: (about=X9 cartridge, retailer B · kind=primary) "),
            s("X9 cartridge $139.", cite("https://www.retailer-b.example/x9", "X9 cartridge $139.")),
        )
        assertEquals("an old price at the same seller is a change over time, another seller is another claim (RES-071)", ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
    }

    @Test fun aSupersededFindingIsNeverCurrentSupport() {
        val r = run(
            "Is the X9 still NSF certified, and should I keep it on my list?",
            s("## ANSWER\nIt is certified [c1]. Keep it on the list.\n"),
            s("## CLAIM c1 · type=fact · load=yes · scope=AquaPure X9 certification · asof=2025-02 · volatile=no · needs_input=no\nThe AquaPure X9 is NSF certified.\nEVIDENCE: (asof=2025-02 · kind=primary) "),
            s("X9: NSF/ANSI 53 certified (2025).", cite("https://www.nsf.example/2025/x9", "X9: NSF/ANSI 53 certified (2025).")),
            s("\n## CLAIM c2 · type=fact · load=yes · scope=AquaPure X9 certification · asof=2026-09 · volatile=no · needs_input=no\nThe AquaPure X9's NSF listing lapsed in September 2026.\nEVIDENCE: (asof=2026-09 · kind=primary) "),
            s("X9 listing lapsed 2026-09.", cite("https://www.nsf.example/listings/x9", "X9 listing lapsed 2026-09.")),
            s("\n## CLAIM c3 · type=recommendation · load=no · scope=- · asof=- · volatile=no · needs_input=no\nKeep the X9 on the list.\nPREMISES: c1\n"),
            s("## CHANGED c1 -> c2\nThe listing lapsed in September 2026; this is a change over time.\n"),
            s("## PROPOSE step · claims=c1 · step=-\nBuy the X9 as the certified option\n"),
            taskLinked = true,
        )
        val c1 = r.claim("c1")!!
        assertTrue(c1.superseded)
        assertTrue("the lead does not present the replaced finding as support", r.answer.lead.none { "c1" in it.claimIds })
        assertTrue("the change itself is shown, as history", r.answer.sections.any { s -> s.kind == SectionKind.CHANGES })
        assertFalse("a recommendation resting on the replaced finding is not established", r.claim("c3")!!.status == ClaimStatus.ESTABLISHED)
        assertTrue("a proposal resting only on the replaced finding is held back", r.proposals.isEmpty())
        assertEquals(1, r.heldBackProposals)
    }
}
