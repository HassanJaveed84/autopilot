package com.lifeadmin.autopilot.testing

// Debug-only fixtures shared by unit tests, Robolectric UI tests and the emulator screenshot tour.
// Fictional sources and figures; never part of a release build.

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSearch
import com.lifeadmin.autopilot.ai.research.RawSearchResult
import com.lifeadmin.autopilot.ai.research.RawSegment
import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.CallStop
import com.lifeadmin.autopilot.ai.usage.MeasuredUsage

/**
 * Model-output shapes in the objective-led contract, one per Document F scenario family, so the
 * same deterministic evidence can prove that different objectives produce different compositions
 * and that no template dictates the shape (RES-011, F §9).
 */
object InvestigationFixtures {
    // ---- R-N(a): simple fact, one authoritative source
    const val UNION_STATION = "address of Union Station, Washington, DC"
    val amtrak = RawCitation("https://www.amtrak.example/stations/was", "Washington Union Station | Amtrak", "Washington Union Station, 50 Massachusetts Ave NE, Washington, DC 20002.")

    fun unionStationAnswer(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nUnion Station, Washington DC: address\n## ANSWER\n"),
            seg("Union Station is at 50 Massachusetts Avenue NE, Washington, DC 20002 [c1].", amtrak),
            seg("\n## CLAIM c1 · type=fact · load=yes · scope=Washington Union Station, DC · asof=2026-10 · volatile=no · needs_input=no\n"),
            seg("The station's address is 50 Massachusetts Ave NE, Washington, DC 20002.\nEVIDENCE: ", null),
            seg("Washington Union Station, 50 Massachusetts Ave NE, Washington, DC 20002.", amtrak),
            seg("\n## UNRESOLVED\n- none\n"),
        ),
        listOf(RawSearch("Union Station Washington DC address", listOf(RawSearchResult(amtrak.url, amtrak.title!!, "October 2026")))),
        MeasuredUsage(5_000, 400, 0, 0, 0, 1), "msg_union",
    )

    // ---- R-E: procedure from an official source, with a circumstance-dependent item
    const val PASSPORT = "What documents do I need to renew my passport?"
    val passportGov = RawCitation("https://www.passports.gov.example/renew", "Renew a passport | Passport Office", "Send your most recent passport, one new photo, the DS-82 form and the fee.")
    val passportNameGov = RawCitation("https://www.passports.gov.example/name-change", "Name changes | Passport Office", "If your name changed, include the original or certified name-change document.")

    fun passportProcedure(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nRenewing a passport: documents\n## ANSWER\n"),
            seg("You need your most recent passport, one new photo, the renewal form and the fee [c1].", passportGov),
            seg(" If your name has changed since the last passport, you also need the name-change document [c2].", passportNameGov),
            seg(" Whether the by-mail renewal applies depends on when your last passport was issued, which only you know [c3].\n"),
            seg("## CLAIM c1 · type=fact · load=yes · scope=renewal by mail, adults · asof=2026-10 · volatile=no · needs_input=no\n"),
            seg("A renewal needs the most recent passport, one new photo, the DS-82 form and the fee.\nEVIDENCE: "),
            seg("Send your most recent passport, one new photo, the DS-82 form and the fee.", passportGov),
            seg("\n## CLAIM c2 · type=fact · load=no · scope=applicants whose name changed · asof=2026-10 · volatile=no · needs_input=no\n"),
            seg("A name change needs the original or certified name-change document.\nEVIDENCE: "),
            seg("If your name changed, include the original or certified name-change document.", passportNameGov),
            seg("\n## CONDITION\nThe by-mail route applies only if your last passport was issued within the last 15 years and you were 16 or older then.\n"),
            seg("## STEPS\n1. Find your most recent passport [c1]\n2. Get one new passport photo [c1]\n3. Fill in the renewal form and pay the fee [c1]\n"),
            seg("## PROPOSE correct · claims=c1 · step=Gather old passport and two photos\nGather your most recent passport and one new photo\nNote: The office asks for one photo, not two.\n"),
            seg("## UNRESOLVED\n- none\n"),
        ),
        listOf(RawSearch("renew passport documents", listOf(RawSearchResult(passportGov.url, passportGov.title!!, null), RawSearchResult(passportNameGov.url, passportNameGov.title!!, null)))),
        MeasuredUsage(9_000, 900, 0, 0, 0, 1), "msg_passport",
    )

    // ---- R-B: neutral comparison, two named options, no winner; one criterion disputed between sources
    const val MULTIS = "Compare Thorne and Pure Encapsulations multivitamins"
    val thorne = RawCitation("https://www.thorne.example/basic-nutrients", "Basic Nutrients 2/Day | Thorne", "Two capsules daily; NSF Certified for Sport.")
    val pure = RawCitation("https://www.pureencapsulations.example/one", "O.N.E. Multivitamin | Pure Encapsulations", "One capsule daily; free from wheat, gluten, nuts.")
    val labReview = RawCitation("https://www.labreview.example/multis", "Independent multivitamin testing", "Both products passed label-claim testing in our 2026 round.")
    val labReviewDup = RawCitation("https://www.labreview.example/multis/", "Independent multivitamin testing", "Both products passed label-claim testing in our 2026 round.")
    val forumPost = RawCitation("https://forum.supps.example/t/thorne-testing", "Thorne third-party testing thread", "A user says Thorne dropped NSF certification on this product in 2025.")

    fun multisCompare(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nThorne vs Pure Encapsulations multivitamins\n## ANSWER\n"),
            seg("Thorne's Basic Nutrients 2/Day is a two-capsule daily formula [c1]", thorne),
            seg(" while Pure Encapsulations O.N.E. is one capsule a day [c2].", pure),
            seg(" Independent label testing passed both [c3],", labReview),
            seg(" but whether Thorne's product currently carries NSF certification is disputed between the brand page and a user report [c4]. Which suits you depends on whether you want a single capsule and on any allergens you avoid.\n"),
            seg("## CLAIM c1 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day · asof=2026-10 · volatile=no · needs_input=no\nThorne Basic Nutrients 2/Day is taken as two capsules daily.\nEVIDENCE: "),
            seg("Two capsules daily; NSF Certified for Sport.", thorne),
            seg("\n## CLAIM c2 · type=fact · load=yes · scope=Pure Encapsulations O.N.E. · asof=2026-10 · volatile=no · needs_input=no\nPure Encapsulations O.N.E. is one capsule daily and free from wheat, gluten and nuts.\nEVIDENCE: "),
            seg("One capsule daily; free from wheat, gluten, nuts.", pure),
            seg("\n## CLAIM c3 · type=fact · load=yes · scope=both products, 2026 testing round · asof=2026 · volatile=no · needs_input=no\nBoth products passed independent label-claim testing in 2026.\nEVIDENCE: "),
            seg("Both products passed label-claim testing in our 2026 round.", labReview),
            seg("\nEVIDENCE: "),
            seg("Both products passed label-claim testing in our 2026 round.", labReviewDup),
            seg("\n## CLAIM c4 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day certification · asof=2026 · volatile=no · needs_input=no\nThorne Basic Nutrients 2/Day is NSF Certified for Sport.\nEVIDENCE: "),
            seg("Two capsules daily; NSF Certified for Sport.", thorne),
            seg("\nAGAINST: "),
            seg("A user says Thorne dropped NSF certification on this product in 2025.", forumPost),
            seg("\n## COMPARE criterion=Daily dose\nThorne: two capsules [c1] | Pure Encapsulations: one capsule [c2]\n"),
            seg("## COMPARE criterion=Independent testing\nThorne: passed 2026 label testing [c3] | Pure Encapsulations: passed 2026 label testing [c3]\n"),
            seg("## COMPARE criterion=Allergen statement\nThorne: not stated on the page found | Pure Encapsulations: free from wheat, gluten, nuts [c2]\n"),
            seg("## RECOMMEND optional=yes\nIf a single capsule matters most, Pure Encapsulations O.N.E. is the simpler routine.\nBecause: it is one capsule a day [c2]\nAssumes: you have no preference about certification\nChanges if: NSF certification matters to you and Thorne's is confirmed\n"),
            seg("## UNRESOLVED\n- Thorne NSF status — brand page and a forum post disagree; no third page settled it\n"),
        ),
        listOf(
            RawSearch("Thorne Basic Nutrients 2/Day", listOf(RawSearchResult(thorne.url, thorne.title!!, null), RawSearchResult(forumPost.url, forumPost.title!!, null))),
            RawSearch("Pure Encapsulations O.N.E. multivitamin", listOf(RawSearchResult(pure.url, pure.title!!, null))),
            RawSearch("independent multivitamin testing 2026", listOf(RawSearchResult(labReview.url, labReview.title!!, "2026"))),
        ),
        MeasuredUsage(22_000, 1_800, 0, 0, 0, 3), "msg_multis",
    )

    // ---- R-F: technical judgment; premises cited, recommendation never cited, conditional on a user-resolvable requirement
    const val SNOWFLAKE = "Should we query Snowflake directly from a customer-facing backend, or replicate to Postgres?"
    val snowDocs = RawCitation("https://docs.snowflake.example/concurrency", "Concurrency limits | Snowflake docs", "A warehouse queues statements beyond its concurrency limit; p95 latency rises under queueing.")
    val pgDocs = RawCitation("https://www.postgresql.example/docs/perf", "Performance | PostgreSQL docs", "Indexed point lookups return in single-digit milliseconds on provisioned hardware.")
    val practitioner = RawCitation("https://engineering.blog.example/snowflake-serving", "Serving customer traffic from a warehouse", "We replicated to Postgres after queueing pushed p95 past 900 ms at peak.")

    fun snowflakeJudgment(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nSnowflake direct vs replicate to Postgres\n## ANSWER\n"),
            seg("Replicating to Postgres is the safer default for a customer-facing path, because a Snowflake warehouse queues statements past its concurrency limit and latency rises under queueing [c1],", snowDocs),
            seg(" while indexed Postgres lookups stay in the single-digit milliseconds [c2].", pgDocs),
            seg(" One team that served customer traffic from a warehouse moved off it after p95 passed 900 ms at peak [c3].", practitioner),
            seg(" If your p95 target is loose and traffic is low, querying directly can be acceptable; that depends on your own latency requirement [c5].\n"),
            seg("## CLAIM c1 · type=fact · load=yes · scope=Snowflake warehouses · asof=2026-10 · volatile=no · needs_input=no\nA Snowflake warehouse queues statements beyond its concurrency limit, raising p95 latency.\nEVIDENCE: "),
            seg("A warehouse queues statements beyond its concurrency limit; p95 latency rises under queueing.", snowDocs),
            seg("\n## CLAIM c2 · type=fact · load=yes · scope=PostgreSQL indexed lookups · asof=2026-10 · volatile=no · needs_input=no\nIndexed Postgres point lookups return in single-digit milliseconds.\nEVIDENCE: "),
            seg("Indexed point lookups return in single-digit milliseconds on provisioned hardware.", pgDocs),
            seg("\n## CLAIM c3 · type=fact · load=no · scope=one practitioner report · asof=2025 · volatile=no · needs_input=no\nOne team moved off direct warehouse serving after p95 passed 900 ms at peak.\nEVIDENCE: "),
            seg("We replicated to Postgres after queueing pushed p95 past 900 ms at peak.", practitioner),
            seg("\n## CLAIM c4 · type=recommendation · load=no · scope=- · asof=- · volatile=no · needs_input=no\nReplicate to Postgres for the customer-facing path.\nPREMISES: c1, c2\n"),
            seg("## CLAIM c5 · type=inference · load=yes · scope=your service · asof=- · volatile=no · needs_input=yes\nWhether direct querying is acceptable depends on your p95 latency target and traffic.\n"),
            seg("## RECOMMEND optional=no\nReplicate to Postgres for the customer-facing path.\nBecause: warehouse queueing raises latency under concurrency [c1]\nBecause: indexed Postgres lookups are consistently fast [c2]\nAssumes: your p95 target is under a few hundred milliseconds\nChanges if: your traffic is low and a looser latency target is acceptable\n"),
            seg("## UNRESOLVED\n- none\n"),
        ),
        listOf(
            RawSearch("Snowflake concurrency limit latency", listOf(RawSearchResult(snowDocs.url, snowDocs.title!!, null), RawSearchResult(practitioner.url, practitioner.title!!, "2025"))),
            RawSearch("Postgres indexed lookup latency", listOf(RawSearchResult(pgDocs.url, pgDocs.title!!, null))),
        ),
        MeasuredUsage(20_000, 2_000, 0, 0, 0, 2), "msg_snow",
    )

    // ---- R-H: no credible support; the model says what it searched, never "it is false"
    const val UNVERIFIABLE = "Does the AquaPure X9 filter remove 99.99% of microplastics?"
    val aquaForum = RawCitation("https://forum.water.example/t/aquapure-x9", "AquaPure X9 thread", "Someone says it removes nearly all microplastics but gives no test.")

    fun unverifiableClaim(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nAquaPure X9 microplastics claim\n## ANSWER\n"),
            seg("No test report or independent measurement supporting the 99.99% figure was found [c1]; the only mention is a forum post without data [c1]. That the claim does not exist on the maker's page does not mean it is false.\n", null),
            seg("## CLAIM c1 · type=fact · load=yes · scope=AquaPure X9 microplastic removal rate · asof=2026-10 · volatile=no · needs_input=no\nThe AquaPure X9 removes 99.99% of microplastics.\nEVIDENCE: "),
            seg("Someone says it removes nearly all microplastics but gives no test.", aquaForum),
            seg("\n## UNRESOLVED\n- AquaPure X9 removal rate — searched the maker's site, two retailers and one lab-testing site; no measurement found\n"),
        ),
        listOf(RawSearch("AquaPure X9 microplastics test", listOf(RawSearchResult(aquaForum.url, aquaForum.title!!, null))), RawSearch("AquaPure X9 lab report", emptyList())),
        MeasuredUsage(12_000, 700, 0, 0, 0, 2), "msg_aqua",
    )

    /** R-G variant: searched, nothing found at all; the claim has no evidence and the unresolved line says what was tried. */
    fun unverifiableNothingFound(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nAquaPure X9 microplastics claim\n## ANSWER\nNo test report, listing or independent measurement of the 99.99% figure could be found [c1].\n"),
            seg("## CLAIM c1 · type=fact · load=yes · scope=AquaPure X9 microplastic removal rate · asof=2026-10 · volatile=no · needs_input=no\nThe AquaPure X9 removes 99.99% of microplastics.\n"),
            seg("## UNRESOLVED\n- AquaPure X9 removal rate — searched the maker's site, two retailers and one lab-testing site; no measurement found\n"),
        ),
        listOf(RawSearch("AquaPure X9 microplastics test", emptyList()), RawSearch("AquaPure X9 lab report", emptyList())),
        MeasuredUsage(9_000, 400, 0, 0, 0, 2), "msg_aqua_none",
    )

    /** A reply whose only content is an instruction to the app: dropped as injection; nothing usable. */
    fun serverFailure(status: Int = 529): CallResult.Failed = CallResult.Failed(com.lifeadmin.autopilot.ai.research.ResearchError.ServerError(status), 1, MeasuredUsage(null, null, null, null, null, 1), 3_000)

    /** Second pass fixture: the open claim is now checked and rewritten with fresh evidence (verify stage). */
    fun multisVerifyResolvesCertification(): CallResult.Done = done(
        listOf(
            seg("## CLAIM c4 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day certification · asof=2026-10 · volatile=no · needs_input=no\nThorne Basic Nutrients 2/Day is NSF Certified for Sport.\nEVIDENCE: "),
            seg("Basic Nutrients 2/Day: NSF Certified for Sport, certificate renewed 2026.", RawCitation("https://www.nsf.example/listing/thorne-bn", "NSF listing | Thorne Basic Nutrients", "Basic Nutrients 2/Day: NSF Certified for Sport, certificate renewed 2026.")),
            seg("\n## UNRESOLVED\n- none\n"),
        ),
        listOf(RawSearch("NSF Certified for Sport Thorne Basic Nutrients", listOf(RawSearchResult("https://www.nsf.example/listing/thorne-bn", "NSF listing | Thorne Basic Nutrients", "2026")))),
        MeasuredUsage(8_000, 500, 0, 0, 2_000, 1), "msg_multis_verify",
    )

    // ---- R-D: a gift recommendation with a budget; prices are volatile, the follow-up changes the budget
    const val FRAGRANCE = "Best men's fragrance under $150 in the US as a gift"
    val deptStore = RawCitation("https://www.deptstore.example/sauvage-edt-100", "Dior Sauvage EDT 100 ml", "Dior Sauvage Eau de Toilette 100 ml, $128.00, in stock.")
    val reviewSite = RawCitation("https://www.scentreview.example/sauvage", "Sauvage EDT review", "Lasts about eight hours on skin; strong projection for the first two.")
    val discounter = RawCitation("https://www.discounter.example/bleu-edt-50", "Bleu de Chanel EDT 50 ml", "Bleu de Chanel Eau de Toilette 50 ml, $95.00, ships from the US.")

    fun fragranceGather(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nA men's fragrance gift under $150\n## ANSWER\n"),
            seg("Dior Sauvage EDT (100 ml) is the pick: it is $128 at a US department store [c1]", deptStore),
            seg(" and lasts about eight hours on skin [c2].", reviewSite),
            seg(" It is a safe gift because it is widely liked and inside the budget [c3].\n"),
            seg("## CLAIM c1 · type=fact · load=yes · scope=Dior Sauvage EDT 100 ml, US department store · asof=2026-10-23 · volatile=yes · needs_input=no\nDior Sauvage EDT 100 ml costs $128 at a US department store.\nEVIDENCE: "),
            seg("Dior Sauvage Eau de Toilette 100 ml, $128.00, in stock.", deptStore),
            seg("\n## CLAIM c2 · type=fact · load=yes · scope=Dior Sauvage EDT · asof=2026 · volatile=no · needs_input=no\nDior Sauvage EDT lasts about eight hours on skin.\nEVIDENCE: "),
            seg("Lasts about eight hours on skin; strong projection for the first two.", reviewSite),
            seg("\n## CLAIM c3 · type=recommendation · load=no · scope=- · asof=- · volatile=no · needs_input=no\nDior Sauvage EDT is the gift pick under $150.\nPREMISES: c1, c2\n"),
            seg("## RECOMMEND optional=no\nDior Sauvage EDT, 100 ml.\nBecause: it is $128, inside the budget [c1]\nBecause: it lasts a full day [c2]\nAssumes: the recipient likes a fresh, spicy scent\nChanges if: the budget drops below about $120\n"),
            seg("## UNRESOLVED\n- none\n"),
        ),
        listOf(RawSearch("best men's fragrance gift under 150", listOf(RawSearchResult(deptStore.url, deptStore.title!!, "October 2026"), RawSearchResult(reviewSite.url, reviewSite.title!!, null)))),
        MeasuredUsage(14_000, 1_100, 0, 0, 0, 2), "msg_frag",
    )

    /** Follow-up "what about under $100?": the price claim is checked fresh and replaced; the longevity claim is reused. */
    fun fragranceFollowUp(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nA men's fragrance gift under $100\n## ANSWER\n"),
            seg("Under $100 the pick changes: Bleu de Chanel EDT (50 ml) is $95 at a US discounter [c4].", discounter),
            seg(" Sauvage no longer fits; its 100 ml bottle is still $128 [c1].\n", deptStore),
            seg("## CLAIM c4 · type=fact · load=yes · scope=Bleu de Chanel EDT 50 ml, US discounter · asof=2026-10-24 · volatile=yes · needs_input=no\nBleu de Chanel EDT 50 ml costs $95 at a US discounter.\nEVIDENCE: "),
            seg("Bleu de Chanel Eau de Toilette 50 ml, $95.00, ships from the US.", discounter),
            seg("\n## CLAIM c1 · type=fact · load=yes · scope=Dior Sauvage EDT 100 ml, US department store · asof=2026-10-24 · volatile=yes · needs_input=no\nDior Sauvage EDT 100 ml costs $128 at a US department store.\nEVIDENCE: "),
            seg("Dior Sauvage Eau de Toilette 100 ml, $128.00, in stock.", deptStore),
            seg("\n## CLAIM c5 · type=recommendation · load=no · scope=- · asof=- · volatile=no · needs_input=no\nBleu de Chanel EDT 50 ml is the gift pick under $100.\nPREMISES: c4\n"),
            seg("## CHANGED c3 -> c5\nThe pick changed because the budget changed, not because anything about Sauvage changed.\n"),
            seg("## RECOMMEND optional=no\nBleu de Chanel EDT, 50 ml.\nBecause: it is $95, inside the new budget [c4]\nAssumes: a 50 ml bottle is acceptable as a gift\nChanges if: the budget goes back above $128\n"),
            seg("## UNRESOLVED\n- none\n"),
        ),
        listOf(RawSearch("men's fragrance gift under 100 US", listOf(RawSearchResult(discounter.url, discounter.title!!, "October 2026"), RawSearchResult(deptStore.url, deptStore.title!!, "October 2026")))),
        MeasuredUsage(12_000, 900, 0, 0, 2_500, 2), "msg_frag_2",
    )

    // ---- Deep passes on the supplement comparison: verify keeps the disagreement, reconcile explains it as a change over time, synthesis writes the result
    val nsfListing = RawCitation("https://www.nsf.example/listing/thorne-bn", "NSF listing | Thorne Basic Nutrients", "Basic Nutrients 2/Day: NSF Certified for Sport, certificate lapsed March 2025, renewed January 2026.")

    fun multisVerifyStillContested(): CallResult.Done = done(
        listOf(
            seg("## CLAIM c4 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day certification · asof=2026 · volatile=no · needs_input=no\nThorne Basic Nutrients 2/Day is NSF Certified for Sport.\nEVIDENCE: "),
            seg("Two capsules daily; NSF Certified for Sport.", thorne),
            seg("\nAGAINST: "),
            seg("A user says Thorne dropped NSF certification on this product in 2025.", forumPost),
            seg("\n## UNRESOLVED\n- Thorne NSF status — the brand page and the forum post still disagree; no registry page found yet\n"),
        ),
        listOf(RawSearch("Thorne Basic Nutrients NSF certification 2025", listOf(RawSearchResult(forumPost.url, forumPost.title!!, "2025")))),
        MeasuredUsage(7_000, 400, 0, 0, 2_000, 1), "msg_multis_verify2",
    )

    fun multisReconcile(): CallResult.Done = done(
        listOf(
            seg("## CLAIM c5 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day certification, 2025–2026 · asof=2026-01 · volatile=no · needs_input=no\nThorne Basic Nutrients 2/Day lost its NSF Certified for Sport listing in March 2025 and regained it in January 2026.\nEVIDENCE: "),
            seg("Basic Nutrients 2/Day: NSF Certified for Sport, certificate lapsed March 2025, renewed January 2026.", nsfListing),
            seg("\nEVIDENCE: "),
            seg("A user says Thorne dropped NSF certification on this product in 2025.", forumPost),
            seg("\n## CHANGED c4 -> c5\nNot a disagreement: the forum post described the 2025 lapse; the brand page describes the current, renewed certificate.\n"),
            seg("## UNRESOLVED\n- none\n"),
        ),
        listOf(RawSearch("NSF Certified for Sport Thorne Basic Nutrients listing", listOf(RawSearchResult(nsfListing.url, nsfListing.title!!, "January 2026")))),
        MeasuredUsage(9_000, 600, 0, 0, 2_000, 1), "msg_multis_reconcile",
    )

    fun multisSynthesis(): CallResult.Done = done(
        listOf(
            seg("## TITLE\nThorne vs Pure Encapsulations multivitamins\n## ANSWER\nThorne's Basic Nutrients 2/Day is two capsules a day [c1] and Pure Encapsulations O.N.E. is one [c2]; both passed independent label testing in 2026 [c3]. Thorne's NSF Certified for Sport listing lapsed in 2025 and was renewed in January 2026 [c5], so it is current now.\n"),
            seg("## COMPARE criterion=Daily dose\nThorne: two capsules [c1] | Pure Encapsulations: one capsule [c2]\n"),
            seg("## COMPARE criterion=Independent testing\nThorne: passed 2026 label testing [c3] | Pure Encapsulations: passed 2026 label testing [c3]\n"),
            seg("## COMPARE criterion=Certification\nThorne: NSF Certified for Sport, renewed 2026 [c5] | Pure Encapsulations: not stated on the page found\n"),
            seg("## RECOMMEND optional=yes\nIf a single capsule matters most, Pure Encapsulations O.N.E. is the simpler routine.\nBecause: it is one capsule a day [c2]\nAssumes: you have no preference about certification\nChanges if: NSF certification matters to you\n"),
            seg("## UNRESOLVED\n- none\n"),
        ),
        emptyList(),
        MeasuredUsage(11_000, 1_200, 0, 0, 3_000, 0), "msg_multis_synth",
    )

    /** Objective-preparation reply for the ambiguous "address of Union Station" (R-N(b)): a clarification, no authorization yet. */
    fun unionStationAmbiguous(): CallResult.Done = done(
        listOf(
            seg("## OBJECTIVE\nFind the street address of a Union Station\n## ASK · kind=fact\n## CLARIFY\nWhich Union Station?\n- Washington, DC\n- Chicago\n- Los Angeles\n- Denver\n## DEPTH · level=quick\nOne authoritative source settles an address.\n"),
        ),
        emptyList(), MeasuredUsage(600, 80, 0, 0, 0, 0), "msg_prep_union",
    )

    /** Objective-preparation reply for R-E: objective clear, jurisdiction missing but not blocking. */
    fun passportObjective(): CallResult.Done = done(
        listOf(
            seg("## OBJECTIVE\nFind the documents required to renew a passport\n## ASK · kind=procedure\n## SATISFIES\n- The current list from the issuing authority\n- Which items depend on circumstances\n## NEEDS\n- The official renewal page\n## UNKNOWN · blocking=no\nWhich country issued the passport\n## CONSEQUENTIAL · no\n## DEPTH · level=standard\nAn official source plus a check of circumstance-dependent items.\n"),
        ),
        emptyList(), MeasuredUsage(700, 120, 0, 0, 0, 0), "msg_prep_passport",
    )

    private fun seg(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun done(segments: List<RawSegment>, searches: List<RawSearch>, usage: MeasuredUsage, id: String) =
        CallResult.Done(segments, searches, usage, CallStop.END, 20_000, id)
}
