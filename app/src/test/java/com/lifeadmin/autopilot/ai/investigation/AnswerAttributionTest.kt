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
 * RES-062/085/086, Document F §4 status honesty: a sentence in the answer is only as strong as the claims it
 * states. Attribution is per sentence (a paragraph often mixes claims), a weak claim's sentence is qualified in
 * plain words rather than dropped, a sentence the model already qualified is left alone, and the main-answer
 * caveats for load-bearing gaps are kept.
 */
class AnswerAttributionTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun run(question: String, vararg reply: RawSegment): TurnResult {
        val objective = ObjectivePreparer.local(question)
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        work.merge(InvestigationParser.parse(reply.toList(), emptyList(), registry, "t1", now), now)
        work.verifyRan = true
        return work.result(objective, TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    private val hours = arrayOf(
        s("## CLAIM c1 · type=fact · load=yes · scope=museum hours · asof=- · volatile=no · needs=direct · needs_input=no\nThe museum opens at 09:00.\nEVIDENCE: (kind=primary) "),
        s("Open daily from 09:00.", cite("https://www.museum.example/visit", "Open daily from 09:00.")),
    )
    private val parking = s("\n## CLAIM c2 · type=fact · load=no · scope=museum parking · asof=- · volatile=no · needs=direct · needs_input=no\nParking is free on Sundays.\n")
    private val ticket = arrayOf(
        s("## CLAIM c3 · type=fact · load=yes · scope=museum day ticket · asof=2026-10 · volatile=yes · needs=direct · needs_input=no\nA day ticket costs $12.\nEVIDENCE: (kind=primary · asof=2026-10) "),
        s("Day ticket $12.", cite("https://www.museum.example/tickets", "October 2026: day ticket $12.")),
        s("\nAGAINST: (kind=secondary · asof=2026-10) "),
        s("Tickets are $15.", cite("https://www.cityguide.example/museum", "Tickets are $15 (October 2026).")),
    )

    @Test fun eachSentenceInAParagraphIsQualifiedByItsOwnClaims() {
        val r = run(
            "When is the museum open and what does a ticket cost?",
            s("## ANSWER\nThe museum opens at 09:00 [c1]. Parking is free on Sundays [c2]. A day ticket costs $12 [c3].\n"),
            *hours, parking, *ticket,
        )
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertEquals(ClaimStatus.NOT_ESTABLISHED, r.claim("c2")!!.status)
        assertEquals(ClaimStatus.CONTESTED, r.claim("c3")!!.status)
        assertEquals("the paragraph stays one paragraph", 1, r.answer.lead.size)
        assertEquals("The museum opens at 09:00. Not established: Parking is free on Sundays. Sources disagree: A day ticket costs $12.", r.answer.lead.single().text)
        assertTrue("citation chips still resolve to the paragraph's claims", r.answer.lead.single().claimIds.containsAll(listOf("c1", "c3")))
        assertTrue("the load-bearing gap is still named as a caveat", r.answer.caveats.any { it.startsWith("Sources disagree") && it.contains("$12") })
        assertEquals(emptyList<String>(), r.answer.lead.flatMap { LeakageScan.scan(it.text) })
    }

    @Test fun aSentenceThatAlreadyStatesItsUncertaintyIsLeftAsWritten() {
        val r = run(
            "When is the museum open?",
            s("## ANSWER\nThe museum opens at 09:00 [c1]. Parking may be free on Sundays, but no source confirms it [c2].\n"),
            *hours, parking,
        )
        assertEquals("The museum opens at 09:00. Parking may be free on Sundays, but no source confirms it.", r.answer.lead.single().text)
    }

    @Test fun aSentenceReportingThatNothingWasFoundIsAlreadyQualified() {
        val r = run(
            "Is parking free?",
            s("## ANSWER\nNo page stating Sunday parking charges could be found [c2]. The museum opens at 09:00 [c1].\n"),
            *hours, parking,
        )
        assertEquals("No page stating Sunday parking charges could be found. The museum opens at 09:00.", r.answer.lead.single().text)
    }

    @Test fun aSentenceMixingAnEstablishedAndAWeakClaimNamesTheWeakPart() {
        val r = run(
            "When is the museum open?",
            s("## ANSWER\nThe museum opens at 09:00 [c1] and parking is free on Sundays [c2].\n"),
            *hours, parking,
        )
        assertEquals("The museum opens at 09:00 and parking is free on Sundays (not established: Parking is free on Sundays).", r.answer.lead.single().text)
    }

    @Test fun conditionsUnmarkedSentencesAndAbbreviationsAreHandledWithoutFalseQualifiers() {
        val r = run(
            "Which plan fits me?",
            s("## ANSWER\nDr. Smith's clinic opens at 09:00 [c1]. Which plan fits depends on how often you visit [c4]. I found no page giving weekend hours.\n"),
            *hours,
            s("\n## CLAIM c4 · type=inference · load=yes · scope=your visits · asof=- · volatile=no · needs_input=yes\nWhich plan fits depends on how often you visit.\n"),
        )
        assertEquals("Dr. Smith's clinic opens at 09:00. Which plan fits depends on how often you visit. I found no page giving weekend hours.", r.answer.lead.single().text)
    }

    @Test fun anUnsupportedSideClaimInTheLiveReplayIsQualified() {
        // R-N(a), the fixed engine's GATHER-only answer: c2 (Columbus Circle) is not established.
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-23.replies.json")
        val at = replay.ranAt.toEpochMilli()
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val x = replay.exchanges.first()
        work.merge(InvestigationParser.parse(x.reply.segments, x.reply.searches, registry, "t1", at), at)
        work.verifyRan = true
        val r = work.result(ObjectivePreparer.local(replay.question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, at)
        assertEquals(ClaimStatus.NOT_ESTABLISHED, r.claim("c2")!!.status)
        val lead = r.answer.lead.joinToString(" ") { it.text }
        assertTrue(lead, lead.contains("Not established: The station fronts Columbus Circle"))
        assertTrue("the established address is stated plainly", lead.startsWith("Union Station in Washington, DC is at 50 Massachusetts Avenue NE"))
        assertFalse(lead, lead.contains("Not established: Union Station"))
    }

    @Test fun storedSegmentsWithoutSentenceAttributionComposeUnchanged() {
        val out = TurnOutput(answer = listOf(AnswerSegment("Parking is free on Sundays.", listOf("c2"))), claims = listOf(Claim("c2", "Parking is free on Sundays.", ClaimType.FACT, loadBearing = false, turnId = "t0")))
        val claims = ClaimAssessor.assess(out.claims, now)
        val a = AnswerComposer.compose(ObjectivePreparer.local("parking?"), out, claims, ResultDeriver.derive(claims, TurnEnding.COMPLETE), "t")
        assertEquals("Not established: Parking is free on Sundays.", a.lead.single().text)
    }
}
