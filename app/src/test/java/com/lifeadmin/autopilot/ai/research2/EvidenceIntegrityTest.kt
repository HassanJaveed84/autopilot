package com.lifeadmin.autopilot.ai.research2

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import com.lifeadmin.autopilot.testing.Research2Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidence-integrity edge cases from the second independent review:
 * 1. a strict budget is Answered only on confirmed, same-currency prices;
 * 2. a restatement with a materially changed value never inherits verification;
 * 3. a brand preference is judged on the product's identity, never on explanatory text.
 */
class EvidenceIntegrityTest {
    private val now = 1_700_000_000_000L

    private fun merged(request: String, vararg calls: CallResult.Done): SessionContent {
        val r = RequirementExtractor.extract(request)
        var s = SessionContent("t", request, r.template, r.requirements, r.requirements.map { Coverage(it.id, RequirementStatus.PENDING) }, outcome = SessionOutcome.INTERRUPTED)
        val registry = SourceRegistry2()
        for (c in calls) s = SessionAssembler.merge(s, ContractParser.parse(c.segments, c.searches, registry, s.requirements, now, s.body.candidates, SessionAssembler.evidenceLines(s)), registry.sources())
        return SessionAssembler.settlePending(s)
    }

    private fun done(vararg segs: RawSegment) = CallResult.Done(segs.toList(), emptyList(), MeasuredUsage(1_000, 100, 0, 0, 0, 0), CallStop.END, 1, "m-x")

    // ------------------------------------------------------------------ 1. budget needs confirmed prices

    @Test fun unconfirmedPriceUnderTheLimitDoesNotSatisfyAStrictBudget() {
        // After the first pass Vanilla Smoke's $79 is written by the model without a citation.
        val s = merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather())
        assertEquals(PriceStatus.NOT_CONFIRMED, s.body.candidates.first { it.id == "c3" }.price!!.status)
        val c0 = s.coverageOf("r-c0")
        assertEquals("an unconfirmed $79 is a price seen, not a price confirmed", RequirementStatus.NEEDS_CHECK, c0.status)
        assertTrue(c0.summary, c0.summary.contains("Vanilla Smoke") && c0.summary.contains("not confirmed"))
        assertFalse(SessionAssembler.covered(s))
        // The second pass cites the price: now the budget is verified and the run is Complete.
        val s2 = merged(Research2Fixtures.FRAGRANCE_REQUEST, Research2Fixtures.fragranceGather(), Research2Fixtures.fragranceFillGaps())
        assertEquals(RequirementStatus.ANSWERED, s2.coverageOf("r-c0").status)
        assertTrue(s2.coverageOf("r-c0").summary.contains("confirmed"))
        assertEquals(SessionOutcome.COMPLETE, SessionAssembler.outcome(s2, SessionAssembler.Ending.NORMAL))
    }

    @Test fun incompatibleCurrencyAndMissingPricesStayNeedsCheckAndOverBudgetStillCounts() {
        val fx = Research2Fixtures.fragranceGather()
        // c3's price confirmed, but in pounds: it cannot be checked against a dollar limit.
        val gbp = fx.copy(segments = fx.segments.flatMap { seg ->
            if (!seg.text.contains("## ATTR c3.price · tag=Fact\n79 USD · 60 ml · Big Retailer\n")) listOf(seg) else listOf(
                seg.copy(text = seg.text.substringBefore("## ATTR c3.price") + "## ATTR c3.price · tag=Fact\n"),
                RawSegment("65 GBP · 60 ml · UK Retailer", listOf(RawCitation("https://www.ukretailer.example/vanilla-smoke", "Vanilla Smoke 60 ml", "£65"))),
                seg.copy(text = "\n" + seg.text.substringAfter("79 USD · 60 ml · Big Retailer\n")),
            )
        })
        val s = merged(Research2Fixtures.FRAGRANCE_REQUEST, gbp)
        val c3 = s.body.candidates.first { it.id == "c3" }
        assertEquals(PriceStatus.CONFIRMED, c3.price!!.status); assertEquals("GBP", c3.price.currency)
        assertEquals(RequirementStatus.NEEDS_CHECK, s.coverageOf("r-c0").status)
        assertTrue(s.coverageOf("r-c0").summary.contains("Vanilla Smoke"))
        // An unconfirmed price ABOVE the limit is still flagged: a violation seen is never waved through.
        val over = fx.copy(segments = fx.segments.map { seg -> seg.copy(text = seg.text.replace("79 USD · 60 ml · Big Retailer", "210 USD · 60 ml · Big Retailer")) })
        val so = merged(Research2Fixtures.FRAGRANCE_REQUEST, over)
        assertEquals(RequirementStatus.PARTLY, so.coverageOf("r-c0").status)
        assertTrue(so.coverageOf("r-c0").summary.contains("Vanilla Smoke"))
    }

    // ------------------------------------------------------------------ 2. restatements with changed values

    @Test fun aChangedValueNeverInheritsVerification() {
        val base = "Two capsules daily provide vitamins A, C, D, E, K and a full B complex."
        // Same statement, allowed variations.
        assertTrue(ContractParser.restates(base, base))
        assertTrue(ContractParser.restates("Two capsules daily provide vitamins A, C, D, E, K and a full B complex", base))
        assertTrue(ContractParser.restates("98.00 USD · 100 ml · Big Retailer", "98.00 USD · 100 ml · Big Retailer"))
        assertTrue(ContractParser.restates("\$98 · 100 ml · Big Retailer", "98.00 USD · 100 ml · Big Retailer"))
        assertTrue(ContractParser.restates("5–6 hours per reviewers", "5–6 hours per reviewers"))
        // Materially changed values: quantity, number word, dosage, unit, currency, price, date, percentage.
        assertFalse("quantity", ContractParser.restates("Three capsules daily provide vitamins A, C, D, E, K and a full B complex.", base))
        assertFalse("number word vs digit with a different value", ContractParser.restates("4 capsules daily provide vitamins A, C, D, E, K and a full B complex.", base))
        assertFalse("dosage", ContractParser.restates("Each capsule provides 2,000 IU (50 mcg) of vitamin D3.", "Each capsule provides 1,000 IU (25 mcg) of vitamin D3."))
        assertFalse("unit", ContractParser.restates("120 g magnesium per capsule, 90 capsules.", "120 mg magnesium per capsule, 90 capsules."))
        assertFalse("currency", ContractParser.restates("98.00 GBP · 100 ml · Big Retailer", "98.00 USD · 100 ml · Big Retailer"))
        assertFalse("price", ContractParser.restates("89.00 USD · 100 ml · Big Retailer", "98.00 USD · 100 ml · Big Retailer"))
        assertFalse("size", ContractParser.restates("98.00 USD · 50 ml · Big Retailer", "98.00 USD · 100 ml · Big Retailer"))
        assertFalse("duration", ContractParser.restates("8–9 hours per reviewers", "5–6 hours per reviewers"))
        assertFalse("date", ContractParser.restates("Renewal fees rise on 1 April 2027 to £94.50.", "Renewal fees rise on 1 April 2026 to £94.50."))
        assertFalse("percentage", ContractParser.restates("Reviewers found 95% of bottles third-party tested.", "Reviewers found 59% of bottles third-party tested."))
        assertFalse("a number added where there was none", ContractParser.restates("Two capsules daily provide vitamins A, C, D, E, K and a full B complex, 90 capsules per bottle.", base))
        assertFalse("a number dropped", ContractParser.restates("Capsules provide vitamins A, C, D, E, K and a full B complex.", base))
    }

    @Test fun aRestatedPriceWithAChangedAmountStaysUnconfirmedEndToEnd() {
        val fr = RequirementExtractor.extract(Research2Fixtures.FRAGRANCE_REQUEST)
        var s = SessionContent("t", Research2Fixtures.FRAGRANCE_REQUEST, fr.template, fr.requirements, outcome = SessionOutcome.INTERRUPTED)
        val registry = SourceRegistry2(runId = "run1")
        val fx = Research2Fixtures.fragranceGather()
        s = SessionAssembler.merge(s, ContractParser.parse(fx.segments, fx.searches, registry, fr.requirements, now), registry.sources())
        val priceSource = s.body.candidates.first { it.id == "c1" }.price!!.refs[0]
        val second = SourceRegistry2(s.sources, runId = "run2")
        // Same retailer and size, different amount, pointing at the page that confirmed $98.
        val out = ContractParser.parse(
            listOf(RawSegment("## ATTR c1.price · tag=Fact\n89.00 USD · 100 ml · Big Retailer src=s$priceSource\n## CLAIM · tag=Reviews\n8–9 hours per reviewers src=s$priceSource\n", emptyList())),
            emptyList(), second, fr.requirements, now, s.body.candidates, SessionAssembler.evidenceLines(s),
        )
        assertEquals(PriceStatus.NOT_CONFIRMED, out.candidates[0].price!!.status)
        assertEquals(emptyList<Int>(), out.candidates[0].price!!.refs)
        assertEquals(ProvenanceTag.SYNTHESIS, out.claims[0].tag)
        assertEquals(emptyList<Int>(), out.claims[0].refs)
        val merged = SessionAssembler.merge(s, out, second.sources())
        assertEquals("the confirmed $98 survives", 98.0, merged.body.candidates.first { it.id == "c1" }.price!!.amount, 0.001)
    }

    // ------------------------------------------------------------------ 3. brand judged on identity, not prose

    @Test fun aPickFromAnotherBrandIsNotThorneBecauseItsTextSaysNotFromThorne() {
        val other = RawCitation("https://www.otherbrand.example/glucosamine-1500", "Glucosamine 1500 | Other Brand", "Glucosamine sulfate 1,500 mg per tablet; not from Thorne.")
        val second = done(
            RawSegment("## REQ r-i3 · status=answered\n", emptyList()),
            RawSegment("Other Brand Glucosamine 1500 is the only option found; it is not from Thorne or Pure Encapsulations.", listOf(other)),
            RawSegment("\n## PICK r-i3 · status=answered\nPick: Other Brand Glucosamine 1500\nLabel: ", emptyList()),
            RawSegment("Glucosamine sulfate 1,500 mg per tablet; not from Thorne.", listOf(other)),
            RawSegment("\nWhy: Not from Thorne or Pure Encapsulations, but the only glucosamine product found.\n## REQ r-c0 · status=answered\nAll picks are from Thorne or Pure Encapsulations.\n", emptyList()),
        )
        val s = merged(Research2Fixtures.SUPPLEMENTS_REQUEST, Research2Fixtures.supplementsGather(), second)
        val c0 = s.coverageOf("r-c0")
        assertEquals("'not from Thorne' in the label text is not a Thorne product", RequirementStatus.YOUR_CALL, c0.status)
        assertTrue(c0.summary, c0.summary.contains("Other Brand Glucosamine 1500"))
        // Shortlist candidates are judged the same way: the name, not the reason.
        val fx = Research2Fixtures.fragranceGather()
        val request = "Brand preference: Brand House. Need recommendations for the best men's perfume, up to \$150 in the USA, as a gift."
        val r = RequirementExtractor.extract(request)
        val brand = r.requirements.first { it.label.startsWith("Brand") }
        val reasoned = fx.copy(segments = fx.segments.map { seg -> seg.copy(text = seg.text.replace("hero: broadly liked, safest for an unknown taste.", "hero: broadly liked; not a Brand House scent but the safest for an unknown taste.")) })
        val sl = merged(request, reasoned)
        assertTrue(sl.coverageOf(brand.id).summary, sl.coverageOf(brand.id).summary.contains("Fresh Aqua"))
        assertEquals(RequirementStatus.YOUR_CALL, sl.coverageOf(brand.id).status)
    }
}
