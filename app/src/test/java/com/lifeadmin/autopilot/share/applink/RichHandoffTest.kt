package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.share.applink.HandoffProblem.BAD_LINK
import com.lifeadmin.autopilot.share.applink.HandoffProblem.DETAIL_WITHOUT_STEP
import com.lifeadmin.autopilot.share.applink.HandoffProblem.DISALLOWED_CHARACTER
import com.lifeadmin.autopilot.share.applink.HandoffProblem.DUPLICATE_FIELD
import com.lifeadmin.autopilot.share.applink.HandoffProblem.INCOMPLETE
import com.lifeadmin.autopilot.share.applink.HandoffProblem.TOO_MANY_ITEMS
import com.lifeadmin.autopilot.share.applink.HandoffProblem.UNKNOWN_FIELD
import com.lifeadmin.autopilot.share.applink.HandoffProblem.VALUE_TOO_LONG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ap=v2` (Rich Steps): step descriptions and labelled links on the same `key=value` grammar, an
 * end marker, and atomic refusal of anything malformed or cut short. Payloads are the ones measured
 * in docs/experiments/rich-steps-handoff; their addresses are realistic shapes, never opened.
 */
class RichHandoffTest {
    private val host = "hassanjaveed84.github.io"
    private val ref = "3f2b8c1e-7d4a-4e6b-9a51-0c8d2e7f6b13"
    private val parser = ImportLinkParser(host)
    private val base = "https://$host/autopilot/import#"

    private fun valid(fragment: String) = (HandoffFragment.parse(fragment) as HandoffParseResult.Valid).handoff
    private fun refused(fragment: String) = (HandoffFragment.parse(fragment) as HandoffParseResult.Invalid).issue.problem
    private fun v2(fields: String) = "ap=v2&r=$ref&t=Gift&s=Buy%20perfume&$fields&end=1"

    private val perfume = LinkHandoff(
        "Thank-you gift for Omar", emptyList(), listOf("Buy Bleu de Chanel EDP 50 mL"), emptyList(), ref, version = 2,
        stepDetails = mapOf(
            0 to HandoffStepDetails(
                listOf(
                    "Estimated price: about \$135 before tax; check the total at checkout.",
                    "Gift packaging: choose the complimentary gift wrap at checkout, or ask for the box and ribbon in store.",
                    "Card: “Omar, thank you for standing next to me. Wear this to the next good night out. — Sam”",
                ),
                listOf(StepLink("https://www.chanel.com/us/fragrance/p/107460/bleu-de-chanel-eau-de-parfum-spray/", "Product page")),
            ),
        ),
    )

    private val research = LinkHandoff(
        "Renew passport before Lisbon trip", emptyList(), listOf("Check renewal requirements and fees"), emptyList(), ref, version = 2,
        stepDetails = mapOf(
            0 to HandoffStepDetails(
                listOf(
                    "You can renew by mail if your passport was issued when you were 16 or older, less than 15 years ago, and isn't damaged.",
                    "Send Form DS-82, the current passport, one photo and the fee by check.",
                ),
                listOf(
                    StepLink("https://travel.state.gov/content/travel/en/passports/have-passport/renew.html", "Renew by mail (DS-82)"),
                    StepLink("https://travel.state.gov/content/travel/en/passports/how-apply/fees.html", "Fees"),
                    StepLink("https://travel.state.gov/content/travel/en/passports/how-apply/processing-times.html", "Processing times"),
                    StepLink("https://travel.state.gov/content/travel/en/passports/how-apply/photos.html", "Photo requirements"),
                    StepLink("https://passportstatus.state.gov/", "Check status"),
                ),
            ),
        ),
    )

    private val threeSteps = LinkHandoff(
        "Thank-you gift for Omar", listOf("Bleu de Chanel EDP, 50 mL, not the Parfum"),
        listOf("Buy Bleu de Chanel EDP 50 mL", "Buy a thank-you card", "Book dinner for Nov 14", "Wrap the gift the night before"),
        listOf("Budget is \$150 for the gift"), ref, version = 2,
        stepDetails = mapOf(
            0 to perfume.stepDetails.getValue(0).copy(description = perfume.stepDetails.getValue(0).description.take(2)),
            1 to HandoffStepDetails(listOf("Plain and cream-coloured, no printed message."), listOf(StepLink("https://www.papyrusonline.com/collections/thank-you-cards", "Thank-you cards"))),
            2 to HandoffStepDetails(
                listOf("Table for 4 at 7:30 pm. Omar likes Italian; ask for a quiet table."),
                listOf(StepLink("https://resy.com/cities/new-york-ny/venues/carbone", "Reserve on Resy"), StepLink("https://www.opentable.com/r/carbone-new-york", "OpenTable")),
            ),
        ),
    )

    @Test fun realisticRichHandoffsRoundTripWellUnderTheLimit() {
        for (h in listOf(perfume, research, threeSteps)) {
            val url = h.toUrl(host)
            assertTrue("${url.length} characters", url.length < 4_000)
            assertEquals(ImportLinkResult.Valid(h), parser.parse(url))
        }
        // Sizes match the feasibility measurements (docs/experiments/rich-steps-handoff/results/sizes.md).
        assertEquals(697, perfume.toUrl(host).length)
    }

    @Test fun theAppLinkPrefixAndEndMarkerFrameEveryV2Link() {
        val url = perfume.toUrl(host)
        assertTrue(url.startsWith(ImportLinkParser.prefix(host, ref, "Thank-you gift for Omar")))
        assertTrue(url.endsWith(ImportLinkParser.END_MARKER))
        assertEquals(1 + 1 + 1 + 1 + 3 + 1 + 1, perfume.fieldCount)
    }

    @Test fun plainV1LinksAreReadExactlyAsBefore() {
        val v1 = LinkHandoff("Book a dental check-up", listOf("Go to Dr. Patel"), listOf("Call dentist", "Ask about X-rays"), listOf("Closed Fridays"), ref)
        val url = v1.toUrl(host)
        assertTrue(url.contains("#ap=v1&") && !url.contains("end=1"))
        val parsed = (parser.parse(url) as ImportLinkResult.Valid).handoff
        assertEquals(v1, parsed)
        assertEquals(1, parsed.version)
        assertTrue(parsed.stepDetails.isEmpty())
        // v2's keys mean nothing in v1, and v1 keeps refusing a raw '='.
        assertEquals(UNKNOWN_FIELD, refused("ap=v1&t=T&s=S&sd=Hello"))
        assertEquals(UNKNOWN_FIELD, refused("ap=v1&t=T&s=S&sl=https://example.com"))
        assertEquals(UNKNOWN_FIELD, refused("ap=v1&t=T&s=S&end=1"))
        assertEquals(DISALLOWED_CHARACTER, refused("ap=v1&t=T&s=a=b"))
    }

    @Test fun linksAreALabelASpaceAndAWebAddress() {
        fun links(f: String) = valid(v2(f)).details(0).links
        val chanel = StepLink("https://www.chanel.com/us/p/107460/", "Product page")
        assertEquals(listOf(chanel), links("sl=Product%20page%20https://www.chanel.com/us/p/107460/"))
        assertEquals(listOf(chanel), links("sl=Product%20page%20https%3A%2F%2Fwww.chanel.com%2Fus%2Fp%2F107460%2F"))
        assertEquals(listOf(StepLink("https://www.chanel.com/us/p/107460/")), links("sl=https://www.chanel.com/us/p/107460/"))
        // ChatGPT's citation parameter (with a raw '=') is accepted and removed; real variants stay.
        assertEquals(listOf(StepLink("https://www.chanel.com/us/p/107460/", "Chanel")), links("sl=Chanel%20https://www.chanel.com/us/p/107460/?utm_source=chatgpt.com"))
        assertEquals(
            listOf(StepLink("https://shop.example.com/p?size=50ml&color=blue", "Shop")),
            links("sl=Shop%20https://shop.example.com/p?size=50ml%26color=blue%26utm_medium=referral"),
        )
        // A file name with an encoded space, written correctly as %2520.
        assertEquals("https://example.gov/forms/DS%2082.pdf", links("sl=Form%20https://example.gov/forms/DS%252082.pdf").single().url)
        assertEquals("http://example.org/page", links("sl=Old%20site%20http://example.org/page").single().url)
        // The first link is the main one; order is kept.
        assertEquals(listOf("A", "B"), links("sl=A%20https://a.example.com&sl=B%20https://b.example.com").map { it.label })
    }

    @Test fun descriptionsAreParagraphsRightAfterTheirStep() {
        val h = valid("ap=v2&t=T&s=One&sd=First%20paragraph&sd=Second%2C%20with%20a%20comma&s=Two&d=Decided&s=Three&sl=https://x.example.com&end=1")
        assertEquals(listOf("One", "Two", "Three"), h.steps)
        assertEquals(listOf("First paragraph", "Second, with a comma"), h.details(0).description)
        assertTrue(h.details(1).isEmpty)
        assertEquals("https://x.example.com", h.details(2).links.single().url)
        assertEquals(listOf("Decided"), h.decisions)
    }

    @Test fun malformedOrTruncatedLinksAreRefusedWhole() {
        assertEquals(INCOMPLETE, refused("ap=v2&t=T&s=Buy&sd=Estimated%20price%3A%20about"))
        assertEquals(INCOMPLETE, refused("ap=v2&t=T&s=Buy&end=1&s=More"))
        assertEquals(DUPLICATE_FIELD, refused("ap=v2&t=T&end=1&s=Buy&end=1"))
        assertEquals(INCOMPLETE, refused("ap=v2"))
        assertEquals(DETAIL_WITHOUT_STEP, refused("ap=v2&t=T&sl=https://example.com&end=1"))
        assertEquals(DETAIL_WITHOUT_STEP, refused("ap=v2&t=T&s=Buy&n=Budget&sd=Late%20detail&end=1"))
        assertEquals(DETAIL_WITHOUT_STEP, refused("ap=v2&t=T&s=Buy&d=Decided&sl=https://example.com&end=1"))
        // A raw '&' inside an address splits it into an unknown field.
        assertEquals(UNKNOWN_FIELD, refused(v2("sl=Shop%20https://shop.example.com/p?size=50ml&color=blue")))
        // '%20' inside an address must be written '%2520'; a decoded space can't be an address.
        assertEquals(BAD_LINK, refused(v2("sl=Form%20https://example.gov/forms/DS%2082.pdf")))
        listOf(
            "sl=Click%20javascript:alert(1)", "sl=Bank%20https://mybank.example@evil.example/login", "sl=Chanel%20www.chanel.com/us",
            "sl=" + "L".repeat(81) + "%20https://example.com", "sl=Mail%20mailto:a@b.example",
        ).forEach { assertEquals(it, BAD_LINK, refused(v2(it))) }
        assertEquals(DISALLOWED_CHARACTER, refused(v2("sl=Open%20intent://scan/#Intent;end")))
        assertEquals(DISALLOWED_CHARACTER, refused(v2("sd=Line%20one%0ALine%20two")))
        assertEquals(TOO_MANY_ITEMS, refused(v2((1..6).joinToString("&") { "sl=https://example.com/$it" })))
        assertEquals(TOO_MANY_ITEMS, refused(v2((1..5).joinToString("&") { "sd=Para%20$it" })))
        assertEquals(VALUE_TOO_LONG, refused(v2("sd=" + "a".repeat(HandoffFragment.MAX_PARAGRAPH + 1))))
        // A raw '#' anchor inside an address is not a legal URL at all.
        assertEquals(ImportLinkResult.Refused(ImportRefusal.NOT_A_LINK), parser.parse(base + v2("sl=Specs%20https://example.com/p#specs")))
        // Every one of these is "didn't come through right" on screen.
        assertEquals(ImportLinkResult.Refused(ImportRefusal.MALFORMED), parser.parse(base + "ap=v2&t=T&s=Buy&sd=cut"))
    }

    @Test fun longAddressesWithQueryStringsFitAndTheWholeLinkIsCapped() {
        val variant = "https://shop.example.com/products/eau-de-parfum?" + (1..40).joinToString("&") { "option$it=value-$it" }
        val h = valid(v2("sl=Variant%20" + variant.replace("&", "%26")))
        assertEquals(variant, h.details(0).links.single().url)
        assertTrue(variant.length > 600)
        val tooLong = "https://shop.example.com/" + "a".repeat(2_001)
        assertEquals(BAD_LINK, refused(v2("sl=$tooLong")))
        // Within every per-field cap, but over the 8,192-character link cap: refused, never cut down.
        val full = LinkHandoff(
            "T", emptyList(), List(10) { "Step $it" }, emptyList(), version = 2,
            stepDetails = (0 until 10).associateWith { HandoffStepDetails(List(4) { "p".repeat(300) }, List(5) { k -> StepLink("https://example.com/$k/" + "u".repeat(150)) }) },
        )
        assertTrue(full.toUrl(host).length > ImportLinkParser.MAX_URL)
        assertEquals(ImportLinkResult.Refused(ImportRefusal.TOO_BIG), parser.parse(full.toUrl(host)))
    }
}
