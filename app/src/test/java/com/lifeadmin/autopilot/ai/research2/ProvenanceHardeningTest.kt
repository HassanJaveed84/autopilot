package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.testing.Research2Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardening finding B — the invariant: a source's existence is not proof that it supports any
 * arbitrary claim. Only provider citations verify; model-typed `src=` ids are pointers, honoured
 * only for sources the provider cited and only as a validated restatement of evidence already held.
 */
class ProvenanceHardeningTest {
    private val now = 1_700_000_000_000L
    private val reqs = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST).requirements

    private fun parse(registry: SourceRegistry2, text: String, cited: List<RawSegment> = emptyList(), searches: List<RawSearch> = emptyList(), known: List<EvidenceLine> = emptyList()) =
        ContractParser.parse(listOf(RawSegment(text, emptyList())) + cited, searches, registry, reqs, now, knownEvidence = known)

    @Test fun searchResultReturnedButNeverCitedIsDiscoveredOnlyAndCannotVerify() {
        val registry = SourceRegistry2()
        val out = parse(registry, "## ANSWER\nx\n## CLAIM · tag=Fact\nThe bottle holds 90 capsules src=s1\n", searches = listOf(RawSearch("q", listOf(RawSearchResult("https://a.example/p", "A", null)))))
        assertEquals(0, registry.sources()[0].citedCount)
        assertFalse(registry.isCited(1))
        assertEquals(emptyList<Int>(), out.claims[0].refs)
        assertEquals("not even a pointer: the page was never cited", emptyList<Int>(), out.claims[0].reusedRefs)
        assertEquals(ProvenanceTag.UNKNOWN, out.claims[0].tag)
    }

    @Test fun modelWrittenSrcOnACitedSourceIsAPointerNotVerification() {
        val registry = SourceRegistry2(listOf(SourceRef2(1, "https://shop.example/x", "X", "shop.example", false, citedCount = 1)))
        val out = parse(registry, "## CAND c1 · name=Thing · variant=- · style=-\nwhy src=s1\n## ATTR c1.price · tag=Fact\n300 USD · 50 ml · Shop src=s1\n## ATTR c1.lasts · tag=Reviews\nAll day src=s1\n## CLAIM · tag=Fact\nMade in Grasse src=s1\n")
        val c = out.candidates[0]
        assertEquals("a model-typed price is never confirmed", PriceStatus.NOT_CONFIRMED, c.price!!.status)
        assertEquals(emptyList<Int>(), c.price.refs)
        assertEquals(ProvenanceTag.UNKNOWN, c.attrs.first { it.key == "price" }.tag)
        assertEquals(listOf(1), c.attrs.first { it.key == "price" }.reusedRefs)
        assertEquals("a sourced tag with only a pointer is Restated, not Reviews", ProvenanceTag.SYNTHESIS, c.attrs.first { it.key == "lasts" }.tag)
        assertEquals(emptyList<Int>(), c.attrs.first { it.key == "lasts" }.refs)
        assertEquals(ProvenanceTag.SYNTHESIS, out.claims[0].tag)
        assertEquals(listOf(1), out.claims[0].reusedRefs)
        assertEquals(emptyList<Int>(), c.refs); assertEquals(listOf(1), c.reusedRefs)
        assertFalse(out.claims[0].text.contains("src="))
    }

    @Test fun providerCitationOnTheAttributeLineStillConfirmsThePrice() {
        val registry = SourceRegistry2()
        val fx = Research2Fixtures.fragranceGather()
        val out = ContractParser.parse(fx.segments, fx.searches, registry, RequirementExtractor.extract(Research2Fixtures.FRAGRANCE_REQUEST).requirements, now)
        val c1 = out.candidates.first { it.id == "c1" }
        assertEquals(PriceStatus.CONFIRMED, c1.price!!.status)
        assertTrue(c1.price.refs.isNotEmpty())
        assertEquals(ProvenanceTag.FACT, c1.attrs.first { it.key == "price" }.tag)
        assertEquals(ProvenanceTag.REVIEWS, c1.attrs.first { it.key == "lasts" }.tag)
        assertTrue(registry.isCited(c1.price.refs[0]))
    }

    @Test fun validatedRestatementKeepsItsEvidenceButANewFactWithAnOldIdDoesNot() {
        // Run 1 holds cited evidence.
        val r = RequirementExtractor.extract(Research2Fixtures.SUPPLEMENTS_REQUEST)
        var s = SessionContent("t", Research2Fixtures.SUPPLEMENTS_REQUEST, r.template, r.requirements, outcome = SessionOutcome.INTERRUPTED)
        val registry = SourceRegistry2(runId = "run1")
        val fx = Research2Fixtures.supplementsGather()
        s = SessionAssembler.merge(s, ContractParser.parse(fx.segments, fx.searches, registry, r.requirements, now), registry.sources())
        val known = SessionAssembler.evidenceLines(s)
        assertTrue(known.any { it.text.startsWith("Two capsules daily") && it.refs == listOf(1) })
        // Run 2 (no search): a restatement, a reworded restatement, and a brand-new fact, all pointing at s1.
        val second = SourceRegistry2(s.sources, runId = "run2")
        val out = parse(
            second,
            "## ANSWER\nTwo capsules daily provide vitamins A, C, D, E, K and a full B complex src=s1. The bottle is on sale this week src=s1.\n" +
                "## CLAIM · tag=Fact\nTwo capsules a day provide vitamins A, C, D, E, K and a full B complex src=s1\n" +
                "## CLAIM · tag=Fact\nContains 500 mg of zinc per capsule src=s1\n",
            known = known,
        )
        assertEquals("verbatim restatement keeps the citation", listOf(1), out.claims[0].refs)
        assertEquals(ProvenanceTag.FACT, out.claims[0].tag)
        assertEquals("a new fact gets no credit from an old source id", emptyList<Int>(), out.claims[1].refs)
        assertEquals(listOf(1), out.claims[1].reusedRefs)
        assertEquals(ProvenanceTag.SYNTHESIS, out.claims[1].tag)
        val answer = out.answer.filter { it.text.isNotBlank() }
        assertEquals(2, answer.size)
        assertEquals(listOf(1), answer[0].refs)
        assertEquals(emptyList<Int>(), answer[1].refs)
        assertEquals(listOf(1), answer[1].reusedRefs)
        assertFalse(answer.any { it.text.contains("src=") })
    }

    @Test fun unknownSourceIdsAreIgnored() {
        val registry = SourceRegistry2(listOf(SourceRef2(1, "https://a.example/p", "A", "a.example", false, citedCount = 1)))
        val out = parse(registry, "## CLAIM · tag=Fact\nSomething src=s9, s42\n## ANSWER\nMore src=s7\n")
        assertEquals(emptyList<Int>(), out.claims[0].refs); assertEquals(emptyList<Int>(), out.claims[0].reusedRefs)
        assertEquals(ProvenanceTag.UNKNOWN, out.claims[0].tag)
        assertEquals(emptyList<Int>(), out.answer.flatMap { it.refs + it.reusedRefs })
        assertEquals(1, registry.sources().size)
    }

    @Test fun citationOnANeighbouringHeadingDoesNotLeakIntoTheNextBlock() {
        val cit = RawCitation("https://a.example/p", "A", "A fact.")
        val registry = SourceRegistry2()
        val out = ContractParser.parse(
            listOf(RawSegment("## CLAIM · tag=Fact\n", emptyList()), RawSegment("A fact.\n## CLAIM · tag=Fact\n", listOf(cit)), RawSegment("B fact.\n", emptyList())),
            emptyList(), registry, reqs, now,
        )
        assertEquals(2, out.claims.size)
        assertEquals(listOf(1), out.claims[0].refs); assertEquals(ProvenanceTag.FACT, out.claims[0].tag)
        assertEquals("the citation covered block B's heading, not its text", emptyList<Int>(), out.claims[1].refs)
        assertEquals(ProvenanceTag.UNKNOWN, out.claims[1].tag)
        // A citation that touches only a heading verifies nothing.
        val onlyHeading = ContractParser.parse(listOf(RawSegment("## CLAIM · tag=Fact", listOf(cit)), RawSegment("\nC fact.\n", emptyList())), emptyList(), SourceRegistry2(), reqs, now)
        assertEquals(emptyList<Int>(), onlyHeading.claims[0].refs)
    }

    @Test fun evidenceIsPreservedAcrossRunsAndAnUnconfirmedValueNeverReplacesAConfirmedOne() {
        val fr = RequirementExtractor.extract(Research2Fixtures.FRAGRANCE_REQUEST)
        var s = SessionContent("t", Research2Fixtures.FRAGRANCE_REQUEST, fr.template, fr.requirements, outcome = SessionOutcome.INTERRUPTED)
        val registry = SourceRegistry2(runId = "run1")
        val fx = Research2Fixtures.fragranceGather()
        s = SessionAssembler.merge(s, ContractParser.parse(fx.segments, fx.searches, registry, fr.requirements, now), registry.sources())
        val before = s.body.candidates.first { it.id == "c1" }
        assertEquals(PriceStatus.CONFIRMED, before.price!!.status)
        val priceSource = before.price.refs[0]
        // Run 2 rewrites c1's price and longevity with pointers only.
        val second = SourceRegistry2(s.sources, runId = "run2")
        val out = ContractParser.parse(
            listOf(RawSegment("## ATTR c1.price · tag=Fact\n105 USD · 100 ml · Other Shop src=s$priceSource\n## ATTR c1.lasts · tag=Reviews\n12 hours src=s$priceSource\n", emptyList())),
            emptyList(), second, fr.requirements, now, s.body.candidates, SessionAssembler.evidenceLines(s),
        )
        assertEquals(PriceStatus.NOT_CONFIRMED, out.candidates[0].price!!.status)
        val merged = SessionAssembler.merge(s, out, second.sources())
        val after = merged.body.candidates.first { it.id == "c1" }
        assertEquals("the confirmed $98 price survives the unconfirmed $105", 98.0, after.price!!.amount, 0.001)
        assertEquals(PriceStatus.CONFIRMED, after.price.status)
        assertEquals("the cited longevity survives the restated one", ProvenanceTag.REVIEWS, after.attrs.first { it.key == "lasts" }.tag)
        assertEquals("5–6 hours per reviewers", after.attrs.first { it.key == "lasts" }.value)
        assertEquals("source numbers are stable across runs", s.sources.map { it.n to it.url }, merged.sources.take(s.sources.size).map { it.n to it.url })
        // A validated restatement of the confirmed price keeps it confirmed.
        val third = SourceRegistry2(s.sources, runId = "run3")
        val re = ContractParser.parse(
            listOf(RawSegment("## ATTR c1.price · tag=Fact\n98.00 USD · 100 ml · Big Retailer src=s$priceSource\n", emptyList())),
            emptyList(), third, fr.requirements, now, s.body.candidates, SessionAssembler.evidenceLines(s),
        )
        assertEquals(PriceStatus.CONFIRMED, re.candidates[0].price!!.status)
        assertEquals(listOf(priceSource), re.candidates[0].price!!.refs)
    }

    @Test fun oneSourceIdentityPerPageWhateverTheFragmentOrSlash() {
        val registry = SourceRegistry2()
        val a = RawCitation("https://a.example/page#top", "A", "x")
        val b = RawCitation("https://a.example/page/", "A", "y")
        ContractParser.parse(listOf(RawSegment("## ANSWER\n", emptyList()), RawSegment("one", listOf(a)), RawSegment(" two", listOf(b))), emptyList(), registry, reqs, now)
        assertEquals(1, registry.sources().size)
        assertEquals(2, registry.sources()[0].citedCount)
    }

    @Test fun restatementMatchingIsStrictAboutDifferentFacts() {
        assertTrue(ContractParser.restates("Two capsules daily provide vitamins A, C, D, E, K and a full B complex.", "Two capsules daily provide vitamins A, C, D, E, K and a full B complex"))
        assertTrue(ContractParser.restates("98.00 USD · 100 ml · Big Retailer", "98.00 USD · 100 ml · Big Retailer"))
        assertFalse(ContractParser.restates("105 USD · 100 ml · Other Shop", "98.00 USD · 100 ml · Big Retailer"))
        assertFalse(ContractParser.restates("Contains 500 mg of zinc per capsule", "Two capsules daily provide vitamins A, C, D, E, K and a full B complex."))
        assertFalse(ContractParser.restates("8+ hours", "5–6 hours per reviewers"))
    }
}
