package com.lifeadmin.autopilot.ai.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Citation accounting (hardening finding A): structured citations in any section count, a source's
 * `citedCount` is the number of cited text blocks that reference it (no double counting across line
 * splits or disagreement sides), uncited search results stay at 0, and prose URLs never count.
 */
class CitationAccountingTest {
    private var n = 0
    private val usage = ResearchUsage(1, 1, 1, "m", 1)
    private val gov = RawCitation("https://www.gov.uk/passport", "Passport", "costs £94.50")
    private val rail = RawCitation("https://www.northlinerail.example/tickets", "Tickets", "12 weeks ahead")

    private fun parse(vararg segs: RawSegment, searches: List<RawSearch> = emptyList()) =
        BriefParser.parse(segs.toList(), searches, usage, false, emptyList()) { "m${++n}" }

    private fun BriefContent.count(url: String) = sources.first { it.url == url }.citedCount

    @Test fun answerOnlyCitationsMakeTheBriefVerified() {
        val b = parse(
            RawSegment("## QUICK ANSWER\n", emptyList()),
            RawSegment("Renewal costs £94.50.", listOf(gov)),
            RawSegment("\n\n## NEXT MOVES\n- STEP: Renew online — cheapest route\n\n## FINDINGS\n- Renewal costs £94.50.\n\n## UNCONFIRMED\n- none", emptyList()),
        )
        assertTrue(b.verified)
        assertEquals(1, b.count(gov.url))
        assertEquals(listOf(1), b.quickAnswer.single().refs)
        // The uncited finding is a suggestion, not promoted by the answer's citation.
        assertTrue(b.findings.single() is Finding.Suggestion)
    }

    @Test fun noHeadingsAnswerOnlyCitationCountsOnce() {
        val b = parse(RawSegment("Renewal costs £94.50.", listOf(gov)))
        assertTrue(b.verified)
        assertEquals(1, b.count(gov.url))
    }

    @Test fun citationsAcrossSectionsCountOncePerCitedBlock() {
        val b = parse(
            RawSegment("## QUICK ANSWER\n", emptyList()),
            RawSegment("Tickets go on sale 12 weeks ahead.", listOf(rail)),
            RawSegment("\n\n## NEXT MOVES\n- STEP: Check the release date — ", emptyList()),
            RawSegment("12 weeks out.", listOf(rail)),
            RawSegment("\n\n## FINDINGS\n- ", emptyList()),
            RawSegment("Released 12 weeks ahead.", listOf(rail)),
            RawSegment("\n- ", emptyList()),
            RawSegment("Renewal costs £94.50.", listOf(gov)),
        )
        assertTrue(b.verified)
        assertEquals(3, b.count(rail.url))
        assertEquals(1, b.count(gov.url))
        assertEquals(listOf(1, 2), b.sources.map { it.n })
    }

    @Test fun aCitedBlockSpanningTwoLinesCountsOnce() {
        val b = parse(
            RawSegment("## FINDINGS\n- ", emptyList()),
            RawSegment("First fact.\n- Second fact in the same cited block.", listOf(rail)),
        )
        assertEquals(1, b.count(rail.url))
        assertEquals(2, b.findings.filterIsInstance<Finding.Sourced>().size)
    }

    @Test fun disagreementSidesAreNotDoubleCounted() {
        val blog = RawCitation("https://travel-tips.example/trains", "Tips", "cheapest at release")
        val b = parse(
            RawSegment("## FINDINGS\n- DISAGREE: When fares are cheapest | ", emptyList()),
            RawSegment("At release", listOf(blog)),
            RawSegment(" | ", emptyList()),
            RawSegment("Varies by train", listOf(rail)),
        )
        assertTrue(b.findings.single() is Finding.Disagree)
        assertEquals(1, b.count(blog.url))
        assertEquals(1, b.count(rail.url))
    }

    @Test fun duplicateCitationsOfOneUrlInOneBlockCountOnce() {
        val twice = listOf(rail, rail.copy(citedText = "another quote from the same page"))
        val b = parse(RawSegment("## QUICK ANSWER\nTwo quotes, one page.", twice))
        assertEquals(1, b.sources.size)
        assertEquals(1, b.count(rail.url))
        assertEquals(2, b.sources.single().quotes.size)
    }

    @Test fun whollyUncitedResponseIsUnverifiedAndResultsStayUncited() {
        val b = parse(
            RawSegment("## QUICK ANSWER\nSee https://www.gov.uk/passport for the fee.\n\n## FINDINGS\n- It costs £94.50 (gov.uk).", emptyList()),
            searches = listOf(RawSearch("passport fee", listOf(RawSearchResult(gov.url, "Passport", "2026")))),
        )
        assertFalse(b.verified)
        assertEquals(1, b.sources.size)
        assertEquals(0, b.count(gov.url))
        assertTrue(b.findings.all { it is Finding.Suggestion })
        assertTrue(b.quickAnswer.none { it.text.contains("http") })
    }
}
