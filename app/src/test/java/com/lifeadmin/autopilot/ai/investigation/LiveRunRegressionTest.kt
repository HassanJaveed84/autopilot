package com.lifeadmin.autopilot.ai.investigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions from the first paid live run, Document F R-N(a) (`docs/live-eval/R-N-a-2026-10-23.md`),
 * replayed offline from the provider's real replies. Each test names the defect it pins. The
 * rules under test are general (freshness, evidence retention across calls, call-scoped narration,
 * leakage, verbatim quotes); nothing here knows what the question was about.
 */
class LiveRunRegressionTest {
    private val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-23.replies.json")
    private val now = replay.ranAt.toEpochMilli()
    private val objective = ObjectivePreparer.local(replay.question)

    /** The historical path: every recorded reply goes through parse → merge exactly as the service feeds them, whether or not a stage would now be skipped. */
    private fun replayAll(upTo: Int = replay.exchanges.size): Pair<TurnWork, SourceRegistry> {
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false, consequential = objective.consequential)
        for (x in replay.exchanges.take(upTo)) {
            val known = work.current()
            val out = InvestigationParser.parse(x.reply.segments, x.reply.searches, registry, "t1", now, known, InvestigationParser.evidenceLines(known))
            work.merge(out, now)
        }
        work.verifyRan = true
        return work to registry
    }

    private fun result(): TurnResult = replayAll().let { (work, registry) -> work.result(objective, TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now) }
    private fun TurnResult.sourceN(urlPart: String): Int = sources.first { urlPart in it.url }.n
    private fun TurnResult.userText(): List<String> = listOf(answer.title) + answer.lead.map { it.text } + answer.caveats + answer.sections.flatMap { s -> s.items.map { it.text } }

    // E — an undated stable fact with a freshness window was treated as stale, so the issuer's page did not settle it and FILL_GAPS ran.
    @Test fun gatherAloneEstablishesTheAddressOnTheIssuersOwnPage() {
        val (work, _) = replayAll(upTo = 1)
        val c1 = work.current().first { it.id == "c1" }
        assertEquals(c1.statusReason, ClaimStatus.ESTABLISHED, c1.status)
        assertTrue("no load-bearing claim is left open, so the gap-filling stage has nothing to do", work.openClaims().isEmpty())
    }

    // A — the gap-filling call's claim block replaced the claim's evidence, dropping the issuer's page gathered earlier in the same turn.
    @Test fun evidenceGatheredEarlierInTheTurnIsKeptWhenALaterCallRestatesTheClaim() {
        val r = result()
        val c1 = r.claim("c1")!!
        val operator = r.sourceN("unionstationdc.com/contact-us")
        assertTrue("the issuer's page cited in GATHER still supports the claim: ${c1.support.map { it.sourceN }}", c1.support.any { it.sourceN == operator && !it.pointerOnly })
        assertEquals(ClaimStatus.ESTABLISHED, c1.status)
        // amtrak.com and greatamericanstations.com are both Amtrak's; hosts are all the engine can tell apart, so it does not call them independent.
        assertFalse(c1.statusReason, c1.statusReason.contains("independent"))
    }

    // A — the later call's answer said a page held by the investigation was not retrieved; the composer kept it.
    @Test fun aLaterCallsNarrationAboutItsOwnSearchDoesNotReachTheAnswer() {
        val lead = result().answer.lead.joinToString(" ") { it.text }
        assertFalse(lead, Regex("""\bthis search\b""", RegexOption.IGNORE_CASE).containsMatchIn(lead))
        assertFalse(lead, lead.contains("did not retrieve", ignoreCase = true))
        assertTrue("the answer itself is untouched", lead.contains("50 Massachusetts Avenue NE"))
    }

    // Leakage gate — "held as s1" reached the user-facing UNRESOLVED section.
    @Test fun noSourceNumberOrWorkflowResidueReachesUserFacingText() {
        val text = result().userText()
        val residue = text.filter { Regex("""\bs\d{1,3}\b""").containsMatchIn(it) }
        assertTrue("internal source numbers in user-facing text: $residue", residue.isEmpty())
        text.forEach { assertEquals(it, emptyList<String>(), LeakageScan.scan(it)) }
    }

    // D — c2 cited a page about its own subject but was labelled about=<a description of the page>; the reason stated that label as a scope mismatch.
    @Test fun aClaimWhoseOnlySourceWasLabelledAsAboutSomethingElseSaysSoWithoutUpgrading() {
        val c2 = result().claim("c2")!!
        assertEquals("the label still lowers it: nothing is upgraded", ClaimStatus.NOT_ESTABLISHED, c2.status)
        assertTrue(c2.statusReason, c2.statusReason.contains("marked", ignoreCase = true))
        assertFalse(c2.statusReason, c2.statusReason.contains("about Wikipedia article", ignoreCase = true))
    }

    // C — model-written evidence lines were shown in quotation marks as if the page said them.
    @Test fun evidenceKeepsThePagesOwnWordsApartFromTheModelsSummary() {
        val r = result()
        val cited = replay.exchanges.flatMap { x -> x.reply.segments.flatMap { it.citations } }
        val amtrak = r.sourceN("amtrak.com/stations/was")
        val e = r.claim("c1")!!.support.first { it.sourceN == amtrak && !it.pointerOnly }
        // The reply's line adds "which operates the station"; the page's cited words do not say it.
        assertTrue(e.excerpt, e.excerpt.contains("which operates the station"))
        val quote = EvidenceDisplay.quoted(e)!!
        assertTrue("only the provider's cited text is quoted: $quote", cited.filter { "amtrak.com/stations/was" in it.url }.any { it.citedText.trim() in quote })
        assertFalse(quote, quote.contains("operates"))
        assertTrue("the model's words are labelled as a summary", EvidenceDisplay.summary(e)!!.startsWith("Claude's summary"))
        // Every verified row on every claim carries the page's own words, and each supports what its claim states.
        for (c in r.claims) for (ev in c.support + c.against) if (!ev.pointerOnly) assertTrue("${c.id} s${ev.sourceN} has no verbatim quote", ev.quote != null)
        assertTrue(r.claim("c1")!!.support.all { it.quote!!.contains("50 Massachusetts") })
        assertTrue(r.claim("c1")!!.against.all { it.quote!!.contains("40 Massachusetts") })
    }

    @Test fun aRowWithoutTheProvidersWordsIsNeverShownAsAQuote() {
        val summaryOnly = EvidenceRef(1, "The page says the office opens at nine.", retrievedAtMs = now, originKey = "a.example")
        assertEquals(null, EvidenceDisplay.quoted(summaryOnly))
        assertEquals("Claude's summary (not the page's words): The page says the office opens at nine.", EvidenceDisplay.summary(summaryOnly))
        val verbatim = summaryOnly.copy(excerpt = "Opens at 09:00.", quote = "Monday to Friday. Opens at 09:00.")
        assertEquals("a line that only repeats the quote adds nothing", null, EvidenceDisplay.summary(verbatim))
    }

    @Test fun aStoredRecordWithoutQuotesStillDecodes() {
        val old = """{"sourceN":2,"excerpt":"x","scopeNote":"","asOf":null,"kind":"PRIMARY","retrievedAtMs":1,"originKey":"a.example","pointerOnly":false}"""
        val e = InvestigationJson.json.decodeFromString(EvidenceRef.serializer(), old)
        assertEquals(null, e.quote)
    }

    // B — `official` is a government-host flag, not authority; the reply's kind= decides sufficiency.
    @Test fun nonGovernmentHostsAreNotOfficialAndSufficiencyFollowsTheStatedKind() {
        val r = result()
        assertTrue(r.sources.none { it.official })
        val c1 = r.claim("c1")!!
        assertTrue(c1.support.any { it.kind == EvidenceKind.PRIMARY })
        assertEquals(ClaimStatus.ESTABLISHED, c1.status)
    }
}
