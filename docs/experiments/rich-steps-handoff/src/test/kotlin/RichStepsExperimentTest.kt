import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import proto.Bridge
import proto.HandoffStep
import proto.HandoffV2
import proto.Item
import proto.LinkHandoff
import proto.Parsed
import proto.Problem
import proto.SavedStep
import proto.StepLink
import proto.buildUrl

/** All URLs below are realistic shapes for measurement only; none was opened or verified. */
object Payloads {
    const val REF = "3f2b8c1e-7d4a-4e6b-9a51-0c8d2e7f6b13" // a 36-character task id, as the app's ids are
    const val HOST = "hassanjaveed84.github.io"

    val perfume = HandoffStep(
        "Buy Bleu de Chanel EDP 50 mL",
        listOf(
            "Estimated price: about \$135 before tax; check the total at checkout.",
            "Gift packaging: choose the complimentary gift wrap at checkout, or ask for the box and ribbon in store.",
            "Card: “Omar, thank you for standing next to me. Wear this to the next good night out. — Sam”",
        ),
        listOf(StepLink("Product page", "https://www.chanel.com/us/fragrance/p/107460/bleu-de-chanel-eau-de-parfum-spray/")),
    )
    val card = HandoffStep(
        "Buy a thank-you card",
        listOf("Plain and cream-coloured, no printed message. Write the note by hand."),
        listOf(StepLink("Thank-you cards", "https://www.papyrusonline.com/collections/thank-you-cards")),
    )
    val dinner = HandoffStep(
        "Book dinner for Nov 14",
        listOf("Table for 4 at 7:30 pm. Omar likes Italian; ask for a quiet table."),
        listOf(StepLink("Reserve on Resy", "https://resy.com/cities/new-york-ny/venues/carbone"), StepLink("OpenTable", "https://www.opentable.com/r/carbone-new-york")),
    )
    val passport = HandoffStep(
        "Check renewal requirements and fees",
        listOf(
            "You can renew by mail if your passport was issued when you were 16 or older, less than 15 years ago, and isn't damaged.",
            "Send Form DS-82, the current passport, one photo and the fee by check. Check current processing times before choosing routine or expedited.",
        ),
        listOf(
            StepLink("Renew by mail (DS-82)", "https://travel.state.gov/content/travel/en/passports/have-passport/renew.html"),
            StepLink("Fees", "https://travel.state.gov/content/travel/en/passports/how-apply/fees.html"),
            StepLink("Processing times", "https://travel.state.gov/content/travel/en/passports/how-apply/processing-times.html"),
            StepLink("Photo requirements", "https://travel.state.gov/content/travel/en/passports/how-apply/photos.html"),
            StepLink("Check status", "https://passportstatus.state.gov/"),
        ),
    )

    val scenarios: List<Pair<String, LinkHandoff>> = listOf(
        "1 perfume step, 3 paragraphs, product URL" to LinkHandoff(2, "Thank-you gift for Omar", emptyList(), listOf(perfume), emptyList(), REF),
        "3 rich steps (gift plan)" to LinkHandoff(2, "Thank-you gift for Omar", emptyList(), listOf(perfume.copy(description = perfume.description.take(2)), card, dinner), emptyList(), REF),
        "Research step, 2 paragraphs, 5 URLs" to LinkHandoff(2, "Renew passport before Lisbon trip", emptyList(), listOf(passport), emptyList(), REF),
        "Plain v1 (1 decision, 3 steps, 1 note)" to LinkHandoff(
            1, "Book a dental check-up", listOf("Go to Dr. Patel, not the chain clinic"),
            listOf(HandoffStep("Call dentist"), HandoffStep("Ask whether X-rays are due"), HandoffStep("Put the appointment in the calendar")),
            listOf("Office opens 8:30, closed Fridays"), REF,
        ),
        "Mixed: 1 d, 1 n, 3 rich + 3 plain steps" to LinkHandoff(
            2, "Thank-you gift for Omar", listOf("Bleu de Chanel EDP, 50 mL, not the Parfum"),
            listOf(perfume.copy(description = perfume.description.take(2)), card, dinner, HandoffStep("Wrap the gift the night before"), HandoffStep("Bring the card to dinner"), HandoffStep("Text Leila the plan")),
            listOf("Budget is \$150 for the gift"), REF,
        ),
        "Worst case allowed by the item caps" to LinkHandoff(
            2, "x".repeat(120), List(5) { "d".repeat(200) },
            List(10) { HandoffStep("s".repeat(200), List(4) { "p".repeat(300) }, List(5) { StepLink("l".repeat(80), "https://example.com/" + "u".repeat(180)) }) },
            List(5) { "n".repeat(200) }, REF,
        ),
    )
}

class RichStepsExperimentTest {
    private val out = StringBuilder()

    @Test
    fun sizes() {
        val urls = StringBuilder()
        out.appendLine("| Scenario | Fields | Canonical URL chars | Tolerant URL chars | v2 parses | 0.2.0 parser says |")
        out.appendLine("|---|---|---|---|---|---|")
        for ((name, h) in Payloads.scenarios) {
            val canonical = buildUrl(h)
            val tolerant = buildUrl(h, HandoffV2::encodeTolerant)
            val fields = canonical.substringAfter('#').split('&').size
            fun v2(u: String) = HandoffV2.parse(u.substringAfter('#'))
            val okC = v2(canonical); val okT = v2(tolerant)
            if (name.startsWith("Worst")) {
                // the caps allow it, the whole-link cap must stop it
                assertIs<Parsed.Valid>(okC)
            } else {
                assertEquals(h, (okC as Parsed.Valid).handoff, name)
                assertEquals(h, (okT as Parsed.Valid).handoff, name)
            }
            val old = orig.ImportLinkParser(Payloads.HOST).parse(canonical)
            val oldSays = when (old) { is orig.ImportLinkResult.Valid -> "Valid"; is orig.ImportLinkResult.Refused -> old.reason.name }
            out.appendLine("| $name | $fields | ${canonical.length} | ${tolerant.length} | yes | $oldSays |")
            urls.appendLine("$name\tC\t$canonical").appendLine("$name\tT\t$tolerant")
        }
        File("build/urls.tsv").writeText(urls.toString())
        File("build/sizes.md").writeText(out.toString())
        println(out)
        println("SAMPLE perfume (canonical):\n" + buildUrl(Payloads.scenarios[0].second))
        println("SAMPLE perfume (tolerant):\n" + buildUrl(Payloads.scenarios[0].second, HandoffV2::encodeTolerant))
    }

    private fun frag(s: String) = "ap=v2&r=${Payloads.REF}&t=Gift&s=Buy%20perfume&$s&end=1"
    private fun problem(f: String) = (HandoffV2.parse(f) as? Parsed.Invalid)?.problem
    private fun links(f: String) = ((HandoffV2.parse(f) as Parsed.Valid).handoff.steps.last().links)

    @Test
    fun v1LinksReadExactlyAsBefore() {
        val v1 = buildUrl(Payloads.scenarios[3].second).substringAfter('#')
        val old = orig.HandoffFragment.parse(v1) as orig.HandoffParseResult.Valid
        val new = (HandoffV2.parse(v1) as Parsed.Valid).handoff
        assertEquals(old.handoff.steps, new.steps.map { it.title })
        assertEquals(old.handoff.decisions, new.decisions); assertEquals(old.handoff.notes, new.notes); assertEquals(old.handoff.ref, new.ref)
        // sd/sl are not part of v1
        assertEquals(Problem.UNKNOWN_FIELD, problem("ap=v1&t=Gift&s=Buy&sd=Hello"))
        // raw '=' stays refused in v1, exactly as 0.2.0 does
        assertEquals(Problem.DISALLOWED_CHARACTER, problem("ap=v1&t=Gift&s=a=b"))
        // set id: a v1 link and the same content as plain v2 map to one set (repeat links still dedupe)
        val asV2 = (HandoffV2.parse(v1.replaceFirst("ap=v1", "ap=v2") + "&end=1") as Parsed.Valid).handoff
        assertEquals(Bridge.setId("task", new), Bridge.setId("task", asV2))
        assertEquals(new.steps, asV2.steps)
        val rich = Payloads.scenarios[0].second
        assertTrue(Bridge.setId("task", rich) != Bridge.setId("task", rich.copy(steps = rich.steps.map { HandoffStep(it.title) })))
    }

    @Test
    fun theOldAppSaysNewerVersionForEveryV2LinkThatFits() {
        val r = orig.ImportLinkParser(Payloads.HOST).parse(buildUrl(Payloads.scenarios[0].second))
        assertEquals(orig.ImportRefusal.NEWER_VERSION, (r as orig.ImportLinkResult.Refused).reason)
    }

    @Test
    fun modelMistakesFailClosed() {
        val results = linkedMapOf<String, String>()
        fun record(name: String, f: String) {
            val p = HandoffV2.parse(f)
            results[name] = if (p is Parsed.Valid) "accepted: " + p.handoff.steps.last().links.joinToString { "[${it.label}] ${it.url}" } else "refused: " + (p as Parsed.Invalid).problem
        }
        record("label + raw https://", frag("sl=Product%20page%20https://www.chanel.com/us/p/107460/"))
        record("label + fully encoded URL", frag("sl=Product%20page%20https%3A%2F%2Fwww.chanel.com%2Fus%2Fp%2F107460%2F"))
        record("URL only, no label", frag("sl=https://www.chanel.com/us/p/107460/"))
        record("ChatGPT citation ?utm_source=chatgpt.com raw =", frag("sl=Chanel%20https://www.chanel.com/us/p/107460/?utm_source=chatgpt.com"))
        record("query with %26 (correct)", frag("sl=Shop%20https://shop.example.com/p?size=50ml%26color=blue"))
        record("query with raw & (mistake)", frag("sl=Shop%20https://shop.example.com/p?size=50ml&color=blue"))
        record("URL path %20 not re-encoded (mistake)", frag("sl=Form%20https://example.gov/forms/DS%2082.pdf"))
        record("URL path %2520 (correct)", frag("sl=Form%20https://example.gov/forms/DS%252082.pdf"))
        record("raw + in label (mistake)", frag("sl=Fees+times%20https://travel.state.gov/fees.html"))
        record("sl before any s", "ap=v2&t=Gift&sl=https://example.com&end=1")
        record("sd after a note, not right after its step", "ap=v2&t=Gift&s=Buy&n=Budget%20150&sd=Late%20detail&end=1")
        record("link cut short (no end=1)", "ap=v2&t=Gift&s=Buy&sd=Estimated%20price%3A%20about")
        record("end=1 not last", "ap=v2&t=Gift&end=1&s=Buy&end=1")
        record("javascript: URL", frag("sl=Click%20javascript:alert(1)"))
        record("intent: URL", frag("sl=Open%20intent://scan/#Intent;end"))
        record("user-info spoof", frag("sl=Bank%20https://mybank.com@evil.example/login"))
        record("no scheme", frag("sl=Chanel%20www.chanel.com/us"))
        record("http (not https)", frag("sl=Old%20site%20http://example.org/page"))
        record("6 links on one step", frag((1..6).joinToString("&") { "sl=https://example.com/$it" }))
        record("5 description paragraphs", frag((1..5).joinToString("&") { "sd=Para%20$it" }))
        record("sd with %0A line break", frag("sd=Line%20one%0ALine%20two"))
        // whole-link level: a raw '#' (an anchor inside a product URL) is not a legal fragment character
        val anchor = "https://${Payloads.HOST}/autopilot/import#" + frag("sl=Specs%20https://example.com/p#specs")
        results["raw # anchor in URL (mistake)"] = when (orig.AppLinkEnvelope.fragment(anchor, Payloads.HOST, "/autopilot/import", 8192)) { orig.AppLinkEnvelope.Result.Refused -> "refused: NOT_A_LINK (java.net.URI rejects a second #)"; else -> "envelope accepted" }
        val sb = StringBuilder("| Model output | Result |\n|---|---|\n")
        results.forEach { (k, v) -> sb.appendLine("| $k | ${v.replace("|", "\\|")} |") }
        File("build/mistakes.md").writeText(sb.toString())
        println(sb)
        assertTrue(results["query with raw & (mistake)"]!!.startsWith("refused"))
        assertTrue(results["javascript: URL"]!!.startsWith("refused"))
        assertTrue(results["user-info spoof"]!!.startsWith("refused"))
        assertTrue(results["link cut short (no end=1)"]!!.startsWith("refused: INCOMPLETE"))
        assertTrue(results["sd after a note, not right after its step"]!!.startsWith("refused: DETAIL_WITHOUT_STEP"))
        assertEquals(links(frag("sl=Product%20page%20https://www.chanel.com/us/p/107460/")), links(frag("sl=Product%20page%20https%3A%2F%2Fwww.chanel.com%2Fus%2Fp%2F107460%2F")))
    }

    @Test
    fun richStepsBecomeOneReviewItemEach() {
        val h = Payloads.scenarios[4].second
        val items = Bridge.build(h, saved = listOf(SavedStep("u1", "Text Leila the plan")))
        // 5 new steps (one existing plain one is skipped, as in v1), each step one unit with its details
        assertEquals(5, items.size)
        val perfume = items[0] as Item.AddStep
        assertEquals(2, perfume.step.description.size); assertEquals(1, perfume.step.links.size)
        val dinner = items[2] as Item.AddStep
        assertEquals(2, dinner.step.links.size)
    }

    @Test
    fun detailsForAnExistingStepKeepTheUsersWordsAndSkipDuplicates() {
        val mine = SavedStep(
            "u1", "Get a passport photo",
            description = "CVS on Main St does them.\nBring glasses off.",
            links = listOf(StepLink(null, "https://www.travel.state.gov/content/travel/en/passports/how-apply/photos.html/")),
        )
        val h = LinkHandoff(
            2, "Renew passport", emptyList(),
            listOf(
                HandoffStep(
                    "get a passport photo!",   // ChatGPT's spelling of the user's step
                    listOf("2 x 2 in, white background, taken in the last 6 months.", "CVS on Main St. does them"),
                    listOf(
                        StepLink("Photo requirements", "https://travel.state.gov/content/travel/en/passports/how-apply/photos.html?utm_source=chatgpt.com"),
                        StepLink("Find a photo location", "https://tools.usps.com/find-location.htm"),
                    ),
                ),
                HandoffStep("Get a passport photo", emptyList(), listOf(StepLink(null, "https://tools.usps.com/find-location.htm#map"))),
            ),
            emptyList(), Payloads.REF,
        )
        val items = Bridge.build(h, listOf(mine))
        assertEquals(1, items.size, "one unit for the step even though ChatGPT named it twice")
        val d = items.single() as Item.AddStepDetails
        assertEquals("u1", d.anchorStepId)
        assertEquals(listOf("2 x 2 in, white background, taken in the last 6 months."), d.description)
        assertEquals(listOf("https://tools.usps.com/find-location.htm"), d.links.map { it.url })
        val after = Bridge.applyDetails(mine, d)
        assertEquals("Get a passport photo", after.title)
        assertEquals("CVS on Main St does them.\nBring glasses off.\n\n2 x 2 in, white background, taken in the last 6 months.", after.description)
        assertEquals(mine.links.first(), after.links.first(), "the user's main link stays main")
        // applying again (e.g. a second link with the same details) changes nothing
        assertEquals(after, Bridge.applyDetails(after, d))
        // plain mention of an existing step proposes nothing, as in v1
        assertEquals(emptyList(), Bridge.build(LinkHandoff(2, "t", emptyList(), listOf(HandoffStep("Get a passport photo")), emptyList()), listOf(mine)))
    }
}
