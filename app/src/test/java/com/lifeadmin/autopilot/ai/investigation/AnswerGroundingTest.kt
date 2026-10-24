package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * N4 (`docs/live-eval/R-N-a-2026-10-24-run3.md` §5): a sentence is only as grounded as the cited text of the claims
 * it names. A claim marker or a citation does not establish everything in a sentence. Checkable details (numbers
 * with their units, names, places, dates) and certainty words must appear in the verbatim cited text; a detail that
 * does not is removed when it is a separable clause, and otherwise named in plain words. A paraphrase that adds
 * nothing checkable passes, and a sentence that already states its uncertainty keeps it. This is a necessary
 * condition, not proof of entailment: see `WordingCheckServiceTest` for the model-assisted check.
 */
class AnswerGroundingTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun run(question: String, vararg reply: RawSegment): TurnResult {
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false, consequential = ObjectivePreparer.local(question).consequential)
        work.merge(InvestigationParser.parse(reply.toList(), emptyList(), registry, "t1", now), now)
        work.verifyRan = true
        return work.result(ObjectivePreparer.local(question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    private fun lead(r: TurnResult) = r.answer.lead.joinToString(" ") { it.text }

    /** Run 3's c2 claim and its two verbatim Wikipedia citations, asked about directly. */
    private val columbus = arrayOf(
        s("## CLAIM c2 · type=fact · load=yes · scope=location of Union Station relative to Columbus Circle · asof=- · volatile=no · needs=direct · needs_input=no\nUnion Station faces Columbus Circle, also called Union Station Plaza.\nEVIDENCE: (kind=secondary · origin=Wikipedia editors) "),
        RawSegment(
            "Columbus Circle, also known as Union Station Plaza or Columbus Plaza, is a traffic circle... located in front of Union Station",
            listOf(
                cite("https://en.wikipedia.org/wiki/Columbus_Circle_(Washington,_D.C.)", ": 80004523\n- Added to NRHP: April 9, 1980\n\nColumbus Circle, also known as Union Station Plaza or Columbus Plaza, is a\n\n\n[traffic circle](https://en.wi..."),
                cite("https://en.wikipedia.org/wiki/Columbus_Circle_(Washington,_D.C.)", "It is located in front of [Union Station](https://en.wikipedia.org/wiki/Union_Station_(Washington,_D.C.)) "),
            ),
        ),
    )

    @Test fun theColumbusCircleIntersectionDetailIsNotShownAsSupported() {
        val r = run(
            "What is in front of Union Station in Washington, DC?",
            s("## ANSWER\nThe building sits at Columbus Circle, also called Union Station Plaza, where Massachusetts Avenue NE meets First Street NE. [c2]\n"),
            *columbus,
        )
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c2")!!.status)
        assertEquals("the unsupported clause is dropped; what the page shows stays", "The building sits at Columbus Circle, also called Union Station Plaza.", lead(r))
    }

    @Test fun aSupportedParaphraseIsLeftAsWritten() {
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-run3.replies.json")
        val at = replay.ranAt.toEpochMilli()
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val x = replay.exchanges.single()
        work.merge(InvestigationParser.parse(x.reply.segments, x.reply.searches, registry, "t1", at), at)
        work.verifyRan = true
        val r = work.result(ObjectivePreparer.local(replay.question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, at)
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertEquals(
            "Union Station in Washington, DC is at 50 Massachusetts Avenue NE, Washington, DC 20002. Amtrak's station page gives the longer ZIP code 20002-4214. The station's own contact page says the same thing. The pages I found do not state an effective date for the address. Street addresses rarely change, so I treat it as current.",
            lead(r),
        )
    }

    @Test fun aSentenceMixingSupportedAndUnsupportedDetailsNamesTheUnsupportedOne() {
        val r = run(
            "When is the city museum open?",
            s("## ANSWER\nThe city museum opens at 09:00 and closes at 18:00 [c1].\n## CLAIM c1 · type=fact · load=yes · scope=city museum opening time · asof=- · volatile=no · needs=direct · needs_input=no\nThe city museum opens at 09:00.\nEVIDENCE: (kind=primary · origin=the city museum) "),
            s("Open daily from 09:00.", cite("https://www.citymuseum.example/visit", "Visiting hours: open daily from 09:00.")),
        )
        assertEquals("The city museum opens at 09:00 and closes at 18:00 (the cited sources do not show: 18:00).", lead(r))
    }

    @Test fun anUnsupportedNumericalSpecificationIsNeverShownAsEstablished() {
        // The claim itself states a strength its cited label does not show.
        val r = run(
            "How much magnesium is in one tablet of the Acme magnesium glycinate?",
            s("## ANSWER\nEach tablet contains 500 mg of magnesium [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Acme magnesium glycinate, per tablet · asof=- · volatile=no · needs=direct · needs_input=no\nEach Acme magnesium glycinate tablet contains 500 mg of magnesium.\nEVIDENCE: (kind=primary · origin=Acme) "),
            s("Magnesium 250 mg per tablet.", cite("https://www.acme.example/magnesium-glycinate", "Supplement facts, per tablet: Magnesium (as magnesium glycinate) 250 mg.")),
        )
        assertEquals(ClaimStatus.INDICATIVE, r.claim("c1")!!.status)
        assertTrue(r.claim("c1")!!.statusReason, r.claim("c1")!!.statusReason.contains("500 mg"))
        assertTrue(lead(r), lead(r).startsWith("Only indicated, not established: Each tablet contains 500 mg"))
        assertEquals("its only load-bearing claim is not established as stated", ResultState.NOT_ESTABLISHED, r.state)
    }

    @Test fun aNumberWithTheWrongUnitIsNotSupportedByTheRightNumber() {
        val r = run(
            "How much vitamin D is in one Acme D3 softgel?",
            s("## ANSWER\nEach softgel has 1000 IU of vitamin D3 [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Acme D3 softgel · asof=- · volatile=no · needs=direct · needs_input=no\nEach Acme D3 softgel contains vitamin D3.\nEVIDENCE: (kind=primary · origin=Acme) "),
            s("Vitamin D3 1000 mcg.", cite("https://www.acme.example/d3", "Per softgel: Vitamin D3 (as cholecalciferol) 1000 mcg.")),
        )
        assertEquals("Each softgel has 1000 IU of vitamin D3 (the cited sources do not show: 1000 IU).", lead(r))
    }

    @Test fun aHealthBenefitStatedMoreStronglyThanItsStudyIsQualified() {
        val r = run(
            "Does creatine help memory?",
            s("## ANSWER\nCreatine improves memory [c1].\n## CLAIM c1 · type=fact · load=yes · scope=creatine and memory in adults · asof=- · volatile=no · needs=direct · needs_input=no\nCreatine improves memory.\nEVIDENCE: (kind=study · origin=a randomized trial) "),
            s("Creatine may improve short-term memory.", cite("https://www.journal.example/creatine-memory", "Creatine supplementation may improve short-term memory in older adults, although the evidence is limited.")),
        )
        assertEquals("Creatine improves memory (the cited evidence is more tentative than this).", lead(r))
    }

    @Test fun anAbsoluteHealthClaimTheEvidenceDoesNotMakeIsQualified() {
        val r = run(
            "Does vitamin D help bones?",
            s("## ANSWER\nVitamin D prevents fractures in older adults [c1].\n## CLAIM c1 · type=fact · load=yes · scope=vitamin D and fractures in older adults · asof=- · volatile=no · needs=review · needs_input=no\nVitamin D with calcium is associated with fewer fractures in older adults.\nEVIDENCE: (kind=review · origin=a systematic review) "),
            s("Fewer fractures with vitamin D plus calcium.", cite("https://www.reviews.example/vitd", "Vitamin D given with calcium was associated with a small reduction in hip fractures in older adults.")),
        )
        assertTrue(lead(r), lead(r).startsWith("Vitamin D prevents fractures in older adults ("))
        assertTrue(lead(r), lead(r).contains("“prevents”"))
    }

    @Test fun aRecommendationBroaderThanItsCitedPremisesIsQualified() {
        val r = run(
            "Which database should I use for my small web app?",
            s("## ANSWER\nSQLite suits small, low-traffic web apps [c1].\n## CLAIM c1 · type=fact · load=yes · scope=SQLite for low-traffic websites · asof=- · volatile=no · needs=direct · needs_input=no\nSQLite works well for most low to medium traffic websites.\nEVIDENCE: (kind=primary · origin=the SQLite project) "),
            s("Works well for low to medium traffic sites.", cite("https://www.sqlite.example/whentouse", "SQLite works great as the database engine for most low to medium traffic websites.")),
            s("\n## CLAIM c2 · type=recommendation · load=yes · scope=database choice · asof=- · volatile=no · needs_input=no\nUse SQLite.\nPREMISES: c1\n## RECOMMEND optional=no\nUse SQLite for every production workload, at any scale.\nBecause: SQLite works well for most low to medium traffic websites [c1]\nAssumes: your app has low to medium traffic\nChanges if: you expect many concurrent writers\n"),
        )
        val rec = r.answer.recommendation!!.text
        assertTrue(rec, rec.startsWith("Use SQLite for every production workload, at any scale ("))
        assertTrue(rec, rec.contains("“for every”") && rec.contains("“at any scale”"))
    }

    @Test fun qualifiedUncertaintyThatMatchesTheEvidenceStaysQualified() {
        val r = run(
            "Does creatine help memory?",
            s("## ANSWER\nCreatine may modestly improve short-term memory in older adults, but the evidence is limited [c1].\n## CLAIM c1 · type=fact · load=yes · scope=creatine and memory in older adults · asof=- · volatile=no · needs=direct · needs_input=no\nCreatine may improve short-term memory in older adults; the evidence is limited.\nEVIDENCE: (kind=study · origin=a randomized trial) "),
            s("Creatine may improve short-term memory.", cite("https://www.journal.example/creatine-memory", "Creatine supplementation may improve short-term memory in older adults, although the evidence is limited.")),
        )
        assertEquals("Creatine may modestly improve short-term memory in older adults, but the evidence is limited.", lead(r))
        assertFalse(lead(r).contains("tentative"))
    }

    @Test fun proceduresAndComparisonsAreCheckedTheSameWay() {
        val steps = run(
            "How do I renew my passport by mail?",
            s("## ANSWER\nRenew by mail with form DS-82 [c1].\n## STEPS\n1. Fill in form DS-82 [c1].\n2. Include 2 photos [c1].\n## CLAIM c1 · type=fact · load=yes · scope=adult passport renewal by mail · asof=- · volatile=no · needs=direct · needs_input=no\nAn adult renews by mail with form DS-82 and one photo.\nEVIDENCE: (kind=primary · origin=the passport agency) "),
            s("Form DS-82 and one photo.", cite("https://www.passports.example/renew-by-mail", "To renew by mail, send Form DS-82 and one photo.")),
        )
        assertEquals(listOf("Fill in form DS-82.", "Include 2 photos (the cited sources do not show: 2)."), steps.answer.sections.single { it.kind == SectionKind.STEPS }.items.map { it.text })

        val table = run(
            "Compare Thorne and Pure Encapsulations multivitamins",
            s("## ANSWER\nThe two differ in servings per bottle [c1, c2].\n## COMPARE criterion=Servings per bottle\nThorne: 60 servings [c1] | Pure Encapsulations: 120 servings [c2]\n## CLAIM c1 · type=fact · load=yes · scope=Thorne Basic Nutrients 2/Day bottle · asof=- · volatile=no · needs=direct · needs_input=no\nA Thorne Basic Nutrients 2/Day bottle holds 60 capsules.\nEVIDENCE: (kind=primary · origin=Thorne) "),
            s("60 capsules, 30 servings.", RawCitation("https://www.thorne.example/basic-nutrients-2-day", "Basic Nutrients 2/Day | Thorne", "60 capsules, 30 servings per bottle.")),
            s("\n## CLAIM c2 · type=fact · load=yes · scope=Pure Encapsulations O.N.E. bottle · asof=- · volatile=no · needs=direct · needs_input=no\nA Pure Encapsulations O.N.E. bottle holds 120 servings.\nEVIDENCE: (kind=primary · origin=Pure Encapsulations) "),
            s("120 servings.", RawCitation("https://www.pureencapsulations.example/one", "O.N.E. Multivitamin | Pure Encapsulations", "120 capsules, 120 servings per bottle.")),
        )
        val cells = table.answer.sections.single { it.kind == SectionKind.COMPARISON }.rows.single().cells.mapValues { it.value.text }
        assertEquals("60 servings (the cited sources do not show: 60 servings)", cells["Thorne"])
        assertEquals("120 servings", cells["Pure Encapsulations"])
    }
}
