package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * RES-046 and the date signal seen in both R-N(a) live runs: a date the reply gives (for example one worked out from
 * a search result's page age) is not the date the fact held unless the cited text shows it. Such a date can make
 * evidence older, never newer; a fact whose date is unknown keeps that uncertainty. Stable facts, current prices and
 * changing schedules follow the same rule; no category sets a window.
 */
class SourceDateIntegrityTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun claim(header: String, text: String, attrs: String, page: String, url: String = "https://www.citypool.example/prices"): Claim {
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val out = InvestigationParser.parse(listOf(s("## ANSWER\n$text [c1].\n## CLAIM c1 · type=fact · load=yes · $header · needs_input=no\n$text\nEVIDENCE: $attrs"), s(page, cite(url, page))), emptyList(), registry, "t1", now)
        work.merge(out, now)
        return work.current().single { it.id == "c1" }
    }

    @Test fun aCurrentPriceDatedOnlyByTheReplyIsNotCurrent() {
        val c = claim("scope=City pool day pass · asof=2026-10 · volatile=yes", "A day pass costs $8.", "(kind=primary · asof=2026-10 (page about 20 days old)) ", "Day pass: $8.")
        assertEquals("the page does not say when $8 applied", ClaimStatus.INDICATIVE, c.status)
    }

    @Test fun aCurrentPriceWhoseDateTheCitedTextShowsIsCurrent() {
        val c = claim("scope=City pool day pass · asof=2026-10 · volatile=yes", "A day pass costs $8.", "(kind=primary · asof=2026-10) ", "Prices from 1 October 2026: day pass $8.")
        assertEquals(ClaimStatus.ESTABLISHED, c.status)
    }

    @Test fun anOldDateGivenByTheReplyStillMakesEvidenceStale() {
        val c = claim("scope=City pool day pass · asof=2025-01 · volatile=yes", "A day pass costs $6.", "(kind=primary · asof=2025-01) ", "Day pass: $6.")
        assertEquals("a date can always make evidence older", ClaimStatus.INDICATIVE, c.status)
        assertTrue(c.statusReason, c.statusReason.contains("2025-01"))
    }

    @Test fun aChangingScheduleNeedsItsDateFromThePage() {
        val dated = claim("scope=Island ferry, winter timetable · asof=2026-11 · volatile=yes · fresh=3m", "The winter ferry runs hourly.", "(kind=primary · asof=2026-11) ", "Winter timetable from 1 November 2026: hourly departures.", "https://www.ferry.example/winter")
        val undated = claim("scope=Island ferry, winter timetable · asof=2026-11 · volatile=yes · fresh=3m", "The winter ferry runs hourly.", "(kind=primary · asof=2026-11) ", "Winter timetable: hourly departures.", "https://www.ferry.example/winter")
        assertEquals(ClaimStatus.ESTABLISHED, dated.status)
        assertEquals(ClaimStatus.INDICATIVE, undated.status)
    }

    @Test fun aStableFactNeedsNoDateAndAReplyDateCannotHurtOrHelpIt() {
        val c = claim("scope=City museum address · asof=2026 · volatile=no · fresh=1y", "The museum is at 1 Example Square.", "(kind=primary · asof=2026 (page about 168 days old at retrieval)) ", "Address: 1 Example Square.", "https://www.museum.example/visit")
        assertEquals(ClaimStatus.ESTABLISHED, c.status)
    }
}
