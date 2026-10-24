package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Document F R-C ("each candidate price as-of now from the seller"; "Answered only with fresh seller-sourced prices;
 * Partially if some unverifiable"). Web search returns the index's copy of a seller page, captured at a time the
 * engine is not told. So the time of the search is never the price's date, nor is the result's page age or a date
 * the reply works out; only a date the cited page text shows dates the price. An undated listing is therefore only
 * indicated, and says why. A seller page that shows its date can establish the price, so R-C's "Answered" stays
 * reachable, and "Partially" is the justified state when it does not.
 */
class PriceFreshnessTest {
    private val now = LocalDate.of(2026, 10, 24).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun run(vararg reply: RawSegment, pageAge: String? = null): TurnResult {
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val searches = listOf(RawSearch("thorne basic nutrients 2/day price", listOf(RawSearchResult("https://www.thorne.example/products/basic-nutrients-2-day", "Basic Nutrients 2/Day | Thorne", pageAge))))
        work.merge(InvestigationParser.parse(reply.toList(), searches, registry, "t1", now), now)
        work.verifyRan = true
        return work.result(ObjectivePreparer.local("Where can I buy Thorne Basic Nutrients 2/Day at the best price from a reputable retailer?"), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    private fun listing(cited: String, asof: String = "-") = arrayOf(
        RawSegment("## ANSWER\nThorne sells Basic Nutrients 2/Day for $36.00 [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day, 60 capsules, Thorne's own store · asof=$asof · volatile=yes · fresh=7d · needs=direct · needs_input=no\nThorne's own store sells Basic Nutrients 2/Day, 60 capsules, for $36.00.\nEVIDENCE: (kind=primary · origin=Thorne · asof=$asof) ", emptyList()),
        RawSegment("Basic Nutrients 2/Day, 60 capsules, $36.00.", listOf(RawCitation("https://www.thorne.example/products/basic-nutrients-2-day", "Basic Nutrients 2/Day | Thorne", cited))),
    )

    @Test fun anUndatedSellerListingIsOnlyIndicatedAndSaysWhy() {
        val r = run(*listing("Basic Nutrients 2/Day · 60 capsules · $36.00 · Add to cart"), pageAge = "1 day ago")
        val c1 = r.claim("c1")!!
        assertEquals("the search time and the page age never date the price", ClaimStatus.INDICATIVE, c1.status)
        assertTrue(c1.statusReason, c1.statusReason.contains("search index's copy of the page") && c1.statusReason.contains("not seen on the live page"))
        assertTrue("its only load-bearing price is not shown as current", r.state != ResultState.ANSWERED)
        assertTrue(r.answer.lead.single().text.startsWith("Only indicated, not established:"))
    }

    @Test fun aDateTheReplyWorksOutDoesNotMakeAnUndatedListingCurrent() {
        val r = run(*listing("Basic Nutrients 2/Day · 60 capsules · $36.00 · Add to cart", asof = "2026-10-24"), pageAge = "1 day ago")
        assertEquals(ClaimStatus.INDICATIVE, r.claim("c1")!!.status)
        assertEquals(DateBasis.STATED, r.claim("c1")!!.support.first().asOfBasis)
    }

    @Test fun aSellerPageThatShowsItsDateCanEstablishTheCurrentPrice() {
        val r = run(*listing("Basic Nutrients 2/Day · 60 capsules · $36.00 · Price as of 23 October 2026", asof = "2026-10-23"))
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertEquals(DateBasis.QUOTED, r.claim("c1")!!.support.first().asOfBasis)
        assertEquals(ResultState.ANSWERED, r.state)
    }

    @Test fun aShownDateOutsideThePricesWindowIsNotCurrent() {
        val r = run(*listing("Basic Nutrients 2/Day · 60 capsules · $36.00 · Price as of 2 September 2026", asof = "2026-09-02"))
        assertEquals(ClaimStatus.INDICATIVE, r.claim("c1")!!.status)
        assertTrue(r.claim("c1")!!.statusReason, r.claim("c1")!!.statusReason.startsWith("As of 2026-09-02"))
    }
}
