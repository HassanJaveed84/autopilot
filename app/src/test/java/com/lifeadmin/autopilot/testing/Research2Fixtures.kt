package com.lifeadmin.autopilot.testing

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.research2.ModelCall
import com.lifeadmin.autopilot.ai.research2.ResearchCallProvider
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Permanent regression fixtures for the M3A.2 benchmarks (brief §12). They are model-output shapes,
 * built from the request contract with provider-style citations — no live API, no key.
 */
object Research2Fixtures {
    const val SUPPLEMENTS_REQUEST = "Brand preference: Thorne and Pure Encapsulations. Need a general multivitamin, vitamin D, magnesium and glucosamine. Produce a list, each as a separate step added to my task."
    const val FRAGRANCE_REQUEST = "Need recommendations for the best men's perfume, up to \$150 in the USA, as a gift."

    val thorneMulti = RawCitation("https://www.thorne.example/products/basic-nutrients", "Basic Nutrients 2/Day | Thorne", "Two capsules daily provide vitamins A, C, D, E, K and a full B complex.")
    val thorneD = RawCitation("https://www.thorne.example/products/vitamin-d-1000", "Vitamin D-1,000 | Thorne", "Each capsule provides 1,000 IU (25 mcg) of vitamin D3.")
    val pureMag = RawCitation("https://www.pureencapsulations.example/magnesium-glycinate", "Magnesium (glycinate) | Pure Encapsulations", "120 mg magnesium per capsule, 90 capsules.")
    val reviewSite = RawCitation("https://www.supplementreviews.example/thorne-basic-nutrients", "Thorne Basic Nutrients review", "Third-party tested; reviewers note the capsule count per bottle.")

    /** Benchmark A: three items found, glucosamine not found for the preferred brands; overlap callout; four proposals. */
    fun supplementsGather(): CallResult.Done = CallResult.Done(
        segments = listOf(
            RawSegment("## TITLE\nThorne & Pure picks: multi, D, magnesium, glucosamine\n## ANSWER\n", emptyList()),
            RawSegment("Thorne's Basic Nutrients 2/Day covers the multivitamin, and Thorne Vitamin D-1,000 covers vitamin D.", listOf(thorneMulti, thorneD)),
            RawSegment(" Pure Encapsulations Magnesium (glycinate) covers magnesium.", listOf(pureMag)),
            RawSegment(" Neither preferred brand lists a plain glucosamine product in the pages found.\n", emptyList()),
            RawSegment("## REQ r-c0 · status=answered\nBoth brands were used for the three picks.\n## REQ r-o0 · status=answered\nOne proposal per item below.\n", emptyList()),
            RawSegment("## REQ r-i0 · status=answered\n", emptyList()),
            RawSegment("Basic Nutrients 2/Day is Thorne's two-capsule multivitamin.", listOf(thorneMulti)),
            RawSegment("\n## REQ r-i1 · status=answered\n", emptyList()),
            RawSegment("Thorne Vitamin D-1,000 provides 1,000 IU per capsule.", listOf(thorneD)),
            RawSegment("\n## REQ r-i2 · status=answered\n", emptyList()),
            RawSegment("Pure Encapsulations Magnesium (glycinate), 120 mg per capsule.", listOf(pureMag)),
            RawSegment("\n## REQ r-i3 · status=not_found\nNo glucosamine product from Thorne or Pure Encapsulations appeared in the searches.\n", emptyList()),
            RawSegment("## PICK r-i0 · status=answered\nPick: Thorne Basic Nutrients 2/Day\nLabel: ", emptyList()),
            RawSegment("Two capsules daily provide vitamins A, C, D, E, K and a full B complex.", listOf(thorneMulti)),
            RawSegment("\nAlternative: Pure Encapsulations O.N.E. Multivitamin\nFor you: Ask your pharmacist whether a multivitamin that already contains vitamin D fits alongside a separate vitamin D product.\nWhy: Fewest capsules among the brand's multivitamins.\n", emptyList()),
            RawSegment("## PICK r-i1 · status=answered\nPick: Thorne Vitamin D-1,000\nLabel: ", emptyList()),
            RawSegment("Each capsule provides 1,000 IU (25 mcg) of vitamin D3.", listOf(thorneD)),
            RawSegment("\nFor you: Ask your doctor what vitamin D level you need before choosing a strength.\nWhy: Standard strength; higher strengths exist.\n", emptyList()),
            RawSegment("## PICK r-i2 · status=answered\nPick: Pure Encapsulations Magnesium (glycinate)\nLabel: ", emptyList()),
            RawSegment("120 mg magnesium per capsule, 90 capsules.", listOf(pureMag)),
            RawSegment("\nFor you: Ask your pharmacist about magnesium alongside any medication you take.\nWhy: Glycinate form is the brand's general-purpose option.\n", emptyList()),
            RawSegment("## PICK r-i3 · status=not_found\nPick: none found\nWhy: Neither brand's pages listed a glucosamine product.\n", emptyList()),
            RawSegment("## CLAIM · tag=Reviews\n", emptyList()),
            RawSegment("Reviewers describe Thorne's multivitamin as third-party tested.", listOf(reviewSite)),
            RawSegment("\n## CLAIM · tag=Fact\nThe multivitamin is cheaper than the others.\n", emptyList()),
            RawSegment("## CALLOUT · kind=cross_item\nThe multivitamin already contains vitamin D, so a separate vitamin D product overlaps with it.\n", emptyList()),
            RawSegment("## CALLOUT · kind=health\nProduct facts, not medical advice.\n", emptyList()),
            RawSegment("## PROPOSE step · req=r-i0\nBuy multivitamin: Thorne Basic Nutrients 2/Day\nNote: Two capsules daily; contains vitamin D.\n", emptyList()),
            RawSegment("## PROPOSE step · req=r-i1\nBuy vitamin D: Thorne Vitamin D-1,000\nNote: 1,000 IU per capsule; ask about your level first.\n", emptyList()),
            RawSegment("## PROPOSE step · req=r-i2\nBuy magnesium: Pure Encapsulations Magnesium (glycinate)\nNote: 120 mg per capsule, 90 capsules.\n", emptyList()),
            RawSegment("## UNRESOLVED\n- Glucosamine from Thorne or Pure Encapsulations — searched both brand sites; no product page found\n", emptyList()),
        ),
        searches = listOf(
            RawSearch("Thorne multivitamin", listOf(RawSearchResult(thorneMulti.url, thorneMulti.title!!, "October 2026"), RawSearchResult(reviewSite.url, reviewSite.title!!, null))),
            RawSearch("Thorne vitamin D", listOf(RawSearchResult(thorneD.url, thorneD.title!!, null))),
            RawSearch("Pure Encapsulations magnesium", listOf(RawSearchResult(pureMag.url, pureMag.title!!, null))),
            RawSearch("Thorne glucosamine", emptyList()),
            RawSearch("Pure Encapsulations glucosamine", emptyList()),
        ),
        usage = MeasuredUsage(41_000, 2_600, 0, 0, 0, 5),
        stop = CallStop.END, elapsedMs = 42_000, providerMessageId = "msg_supp_1",
    )

    val retailerA = RawCitation("https://www.bigretailer.example/fresh-aqua-edt-100", "Fresh Aqua EDT 100 ml", "Fresh Aqua Eau de Toilette, 3.4 oz — \$98.00")
    val retailerB = RawCitation("https://www.brandhouse.example/oud-noir", "Oud Noir Eau de Parfum", "Oud Noir EDP 50 ml \$145")
    val reviewer = RawCitation("https://www.scentcritic.example/best-mens-2026", "Best men's fragrances 2026", "Fresh Aqua remains the safest blind buy; Oud Noir lasts 8+ hours.")
    val forum = RawCitation("https://forum.fragrancefans.example/t/oud-noir-price", "Oud Noir price thread", "Seen at \$120 at a discounter, but that was a tester.")

    /** Benchmark B: three styles, hero for unknown taste, one excluded over budget, conflicting price resolved. */
    fun fragranceGather(): CallResult.Done = CallResult.Done(
        segments = listOf(
            RawSegment("## TITLE\nMen's fragrance gift under \$150 (US): shortlist\n## ANSWER\n", emptyList()),
            RawSegment("For a recipient whose taste you don't know, Fresh Aqua EDT is the safest pick; reviewers call it the safest blind buy.", listOf(reviewer)),
            RawSegment(" Oud Noir EDP is the bolder, longer-lasting choice at \$145.", listOf(retailerB)),
            RawSegment("\n## REQ r-i0 · status=answered\nThree distinct styles found within budget.\n## REQ r-c0 · status=answered\nAll included picks are at or under \$150 at the cited retailers.\n## REQ r-c1 · status=answered\nUS retailers cited.\n## REQ r-c2 · status=your_call\nTaste unknown; the hero pick is the safest.\n## REQ r-o0 · status=answered\nShortlist below.\n", emptyList()),
            RawSegment("## CAND c1 · name=Fresh Aqua · variant=EDT 100 ml · style=fresh\nhero: broadly liked, safest for an unknown taste.\n## ATTR c1.price · tag=Fact\n", emptyList()),
            RawSegment("98.00 USD · 100 ml · Big Retailer", listOf(retailerA)),
            RawSegment("\n## ATTR c1.lasts · tag=Reviews\n", emptyList()),
            RawSegment("5–6 hours per reviewers", listOf(reviewer)),
            RawSegment("\n## ATTR c1.gift · tag=Judgment\nSafe for most tastes; office-friendly.\n", emptyList()),
            RawSegment("## CAND c2 · name=Oud Noir · variant=EDP 50 ml · style=woody\nBold and long-lasting; for someone who likes strong scents.\n## ATTR c2.price · tag=Fact\n", emptyList()),
            RawSegment("145 USD · 50 ml · Brand House", listOf(retailerB)),
            RawSegment("\n## ATTR c2.lasts · tag=Reviews\n", emptyList()),
            RawSegment("8+ hours", listOf(reviewer)),
            RawSegment("\n## ATTR c2.gift · tag=Judgment\nRisky as a blind gift.\n", emptyList()),
            RawSegment("## CAND c3 · name=Vanilla Smoke · variant=EDP 60 ml · style=sweet\nSweet and warm; a third style for variety.\n## ATTR c3.price · tag=Fact\n79 USD · 60 ml · Big Retailer\n## ATTR c3.gift · tag=Judgment\nGood for someone who likes gourmand scents.\n", emptyList()),
            RawSegment("## EXCLUDED c4 · name=Royal Iris Extrait\nOver budget: \$210 at the brand's site.\n", emptyList()),
            RawSegment("## CONFLICT Oud Noir price\nA: ", emptyList()),
            RawSegment("\$145 at Brand House", listOf(retailerB)),
            RawSegment(" | B: ", emptyList()),
            RawSegment("\$120 at a discounter, but a tester", listOf(forum)),
            RawSegment(" | Resolution: relied on the brand price; testers are excluded.\n", emptyList()),
            RawSegment("## CALLOUT · kind=unknown_recipient\nIf you don't know their taste, the fresh pick is the safest; the woody one rewards someone who likes bold scents.\n", emptyList()),
            RawSegment("## PROPOSE decide · req=r-c2\nChoose a style for the gift: fresh (safe), woody (bold) or sweet\nNote: Fresh Aqua EDT 100 ml \$98 · Oud Noir EDP 50 ml \$145 · Vanilla Smoke EDP 60 ml \$79 (price not confirmed)\n", emptyList()),
            RawSegment("## PROPOSE step · req=-\nBuy Fresh Aqua EDT 100 ml\nNote: \$98 at Big Retailer, seen today.\n", emptyList()),
            RawSegment("## UNRESOLVED\n- Vanilla Smoke price — the only page found was a search listing without a price\n", emptyList()),
        ),
        searches = listOf(
            RawSearch("best men's fragrance gift under 150", listOf(RawSearchResult(reviewer.url, reviewer.title!!, "September 2026"))),
            RawSearch("Fresh Aqua EDT price", listOf(RawSearchResult(retailerA.url, retailerA.title!!, null))),
            RawSearch("Oud Noir EDP price", listOf(RawSearchResult(retailerB.url, retailerB.title!!, null), RawSearchResult(forum.url, forum.title!!, null))),
        ),
        usage = MeasuredUsage(36_000, 3_100, 0, 0, 0, 3),
        stop = CallStop.END, elapsedMs = 38_000, providerMessageId = "msg_frag_1",
    )

    /** Second pass that confirms the remaining price with a citation. */
    fun fragranceFillGaps(): CallResult.Done = CallResult.Done(
        segments = listOf(
            RawSegment("## REQ r-c2 · status=your_call\nStill your call; all three prices now confirmed.\n## ATTR c3.price · tag=Fact\n", emptyList()),
            RawSegment("79.00 USD · 60 ml · Big Retailer", listOf(RawCitation("https://www.bigretailer.example/vanilla-smoke-60", "Vanilla Smoke EDP 60 ml", "Vanilla Smoke Eau de Parfum 2 oz \$79.00"))),
        ),
        searches = listOf(RawSearch("Vanilla Smoke EDP 60 ml price", listOf(RawSearchResult("https://www.bigretailer.example/vanilla-smoke-60", "Vanilla Smoke EDP 60 ml", null)))),
        usage = MeasuredUsage(18_000, 400, 0, 0, 2_000, 1),
        stop = CallStop.END, elapsedMs = 9_000, providerMessageId = "msg_frag_2",
    )
}

/** Scripted call provider: answers in order, records every call, can hold a call open for Stop tests. */
class FakeCallProvider : ResearchCallProvider {
    override val id = "fake-calls"
    val calls = CopyOnWriteArrayList<ModelCall>()
    val keysSeen = CopyOnWriteArrayList<String>()
    private val queue = ArrayDeque<(ModelCall) -> CallResult>()
    var gate: CompletableDeferred<Unit>? = null
    /** Gate only the n-th call (1-based); null = every call. */
    var gateCall: Int? = null
    var fallback: (ModelCall) -> CallResult = { CallResult.Failed(com.lifeadmin.autopilot.ai.research.ResearchError.Unexpected("no script"), 0, null, 0) }

    fun enqueue(vararg results: CallResult) { results.forEach { r -> queue.addLast { r } } }
    fun enqueue(block: (ModelCall) -> CallResult) { queue.addLast(block) }

    override suspend fun call(call: ModelCall, apiKey: String): CallResult {
        calls += call
        keysSeen += apiKey
        val g = gate
        if (g != null && (gateCall == null || gateCall == calls.size)) g.await()
        return (queue.removeFirstOrNull() ?: fallback)(call)
    }
}
