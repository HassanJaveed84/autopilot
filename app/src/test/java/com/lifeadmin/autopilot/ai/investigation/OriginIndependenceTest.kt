package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * RES-043/045, Document F §4 evidence grounding ("repeated origin counted as independent"): agreement only
 * corroborates a claim when the agreeing sources are known to be independent. Different websites are not
 * enough. One site's subdomains, a publisher's second site and pages repeating one announcement are one
 * origin; a reply's own word that sources are independent never outweighs those checks. The issuer route
 * is unchanged, so one directly authoritative source is still enough.
 */
class OriginIndependenceTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun claim(needs: String = "corroborated", vararg evidence: Pair<String, Pair<String, String>>, consequential: Boolean = false): Claim {
        val parts = ArrayList<RawSegment>()
        parts += s("## ANSWER\nThe recall covers batch 42 [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Brand kettle recall, batch 42 · asof=2026-10 · volatile=no${if (needs.isNotEmpty()) " · needs=$needs" else ""} · needs_input=no\nThe recall covers batch 42.")
        for ((attrs, page) in evidence) { parts += s("\nEVIDENCE: ($attrs) "); parts += s(page.second, cite(page.first, page.second)) }
        val objective = ObjectivePreparer.local("Is batch 42 of the Brand kettle recalled?").copy(consequential = consequential)
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false, consequential = consequential)
        work.merge(InvestigationParser.parse(parts, emptyList(), registry, "t1", now), now)
        work.verifyRan = true
        return work.result(objective, TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now).claim("c1")!!
    }

    private val release = "Brand is voluntarily recalling kettles from production batch 42 because the lid can detach while the water is still boiling hot."

    @Test fun twoSitesRunByOnePublisherAreOneOrigin() {
        val c = claim(
            "corroborated",
            "kind=secondary" to ("https://www.brand.example/news/recall" to "Batch 42 kettles are being recalled."),
            "kind=secondary · origin=Brand" to ("https://www.brandstories.example/kettle" to "We are recalling batch 42."),
        )
        assertEquals(ClaimStatus.INDICATIVE, c.status)
        assertEquals(1, c.independentOrigins)
    }

    @Test fun pagesRepeatingOneAnnouncementAreOneOriginWhateverTheyCallThemselves() {
        val c = claim(
            "corroborated",
            "kind=secondary · origin=Daily Times" to ("https://www.dailytimes.example/kettle" to release),
            "kind=secondary · origin=City Herald" to ("https://cityherald.example/news/42" to "Update: $release"),
            "kind=secondary · origin=Metro News" to ("https://metronews.example/a/kettle-recall" to "$release Customers should stop using them."),
        )
        assertEquals("three pages carrying the same announcement are one origin", ClaimStatus.INDICATIVE, c.status)
        assertEquals(1, c.independentOrigins)
    }

    @Test fun agreementBetweenSitesOfUnknownIndependenceDoesNotCorroborate() {
        val c = claim(
            "corroborated",
            "kind=secondary" to ("https://www.dailytimes.example/kettle" to "Batch 42 is covered by the recall."),
            "kind=secondary" to ("https://cityherald.example/news/42" to "The recall applies to batch 42."),
        )
        assertEquals(ClaimStatus.INDICATIVE, c.status)
        assertTrue(c.statusReason, c.statusReason.contains("independen", ignoreCase = true))
    }

    @Test fun agreementBetweenSourcesKnownToBeIndependentCorroborates() {
        val c = claim(
            "corroborated",
            "kind=secondary · origin=Daily Times" to ("https://www.dailytimes.example/kettle" to "Batch 42 is covered by the recall, the paper confirmed with the retailer."),
            "kind=study · origin=Consumer Testing Lab" to ("https://testlab.example/reports/kettle" to "Our test unit from batch 42 matched the recall notice."),
        )
        assertEquals(ClaimStatus.ESTABLISHED, c.status)
        assertEquals(2, c.independentOrigins)
    }

    @Test fun aReplyCallingTwoSubdomainsIndependentDoesNotMakeThemSo() {
        val c = claim(
            "corroborated",
            "kind=secondary · origin=Example News" to ("https://news.example.co.uk/kettle" to "Batch 42 is recalled."),
            "kind=secondary · origin=Example Shop" to ("https://shop.example.co.uk/notices/42" to "Batch 42 recall notice."),
        )
        assertEquals(ClaimStatus.INDICATIVE, c.status)
    }

    @Test fun oneDirectlyAuthoritativeSourceIsStillEnough() {
        val c = claim(
            "corroborated",
            "kind=primary" to ("https://www.safety-regulator.example/recalls/123" to "Recall 123: Brand kettles, batch 42."),
        )
        assertEquals(ClaimStatus.ESTABLISHED, c.status)
        assertEquals("The issuing source, in scope", c.statusReason)
        val direct = claim(
            "direct",
            "kind=secondary" to ("https://www.dailytimes.example/kettle" to "Batch 42 is covered by the recall."),
        )
        assertEquals("a claim that needs only direct evidence is not held to independent agreement", ClaimStatus.ESTABLISHED, direct.status)
    }

    @Test fun theContractAsksForTheOriginAndTheParserKeepsIt() {
        assertTrue(InvestigationPrompt.SYSTEM.contains("origin=<who first published this information>"))
        val (attrs, excerpt) = EvidenceAttrs.split(" (kind=secondary · origin=Brand · asof=-) Batch 42 is recalled.", "batch 42")
        assertEquals("Brand", attrs.origin)
        assertEquals("Batch 42 is recalled.", excerpt)
        assertEquals(emptyList<String>(), LeakageScan.scan(LeakageScan.clean("Batch 42 origin=Brand is recalled.")))
    }

    @Test fun aConsequentialLoadBearingClaimWithNoStandardNeedsKnownIndependence() {
        val c = claim(
            "",
            "kind=secondary" to ("https://www.dailytimes.example/kettle" to "Batch 42 is covered by the recall."),
            "kind=secondary" to ("https://cityherald.example/news/42" to "The recall applies to batch 42."),
            consequential = true,
        )
        assertEquals(ClaimStatus.INDICATIVE, c.status)
    }
}
