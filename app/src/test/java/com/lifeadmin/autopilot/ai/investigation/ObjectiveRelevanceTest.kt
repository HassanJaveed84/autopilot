package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * N3 (`docs/live-eval/R-N-a-2026-10-24-run3.md` §5), Document F §4 Presentation "nothing included because found":
 * optional material is shown only when it bears on the objective's answer (a risk in acting on it, an exception
 * to it, a limitation of it, or something needed to act on it). Background is omitted, not shown with a
 * disclaimer. No category or topic decides this; the objective's answer and the item's stated bearing do.
 */
class ObjectiveRelevanceTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun run(question: String, vararg reply: RawSegment): TurnResult {
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        work.merge(InvestigationParser.parse(reply.toList(), emptyList(), registry, "t1", now), now)
        work.verifyRan = true
        return work.result(ObjectivePreparer.local(question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    private fun material(r: TurnResult) = r.answer.sections.filter { it.kind == SectionKind.MATERIAL }.flatMap { it.items }.map { it.text }

    private val hours = arrayOf(
        s("## ANSWER\nThe city museum opens at 09:00 [c1].\n## CLAIM c1 · type=fact · load=yes · scope=city museum opening time · asof=- · volatile=no · needs=direct · needs_input=no\nThe city museum opens at 09:00.\nEVIDENCE: (kind=primary · origin=the city museum) "),
        s("Open daily from 09:00.", cite("https://www.citymuseum.example/visit", "Visiting hours: open daily from 09:00.")),
    )

    @Test fun theThirdLiveRunsUnrequestedBackgroundItemIsNotShown() {
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-run3.replies.json")
        val at = replay.ranAt.toEpochMilli()
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val x = replay.exchanges.single()
        work.merge(InvestigationParser.parse(x.reply.segments, x.reply.searches, registry, "t1", at), at)
        work.verifyRan = true
        val r = work.result(ObjectivePreparer.local(replay.question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, at)
        assertEquals("the Columbus Circle item answers nothing the address question asked", emptyList<String>(), material(r))
        val lead = r.answer.lead.joinToString(" ") { it.text }
        assertTrue("the address answer itself is unchanged", lead.startsWith("Union Station in Washington, DC is at 50 Massachusetts Avenue NE, Washington, DC 20002. Amtrak's station page gives the longer ZIP code 20002-4214."))
        assertEquals(ResultState.ANSWERED, r.state)
    }

    @Test fun aCorrectlySourcedButIrrelevantFactIsOmittedNotDisclaimed() {
        val r = run(
            "When does the city museum open?",
            *hours,
            s("\n## WORTH_KNOWING\nThe museum building dates from 1907 [c2].\n## CLAIM c2 · type=fact · load=no · scope=city museum building · asof=- · volatile=no · needs=direct · needs_input=no\nThe museum building dates from 1907.\nEVIDENCE: (kind=primary · origin=the city museum) "),
            s("Built in 1907.", cite("https://www.citymuseum.example/history", "The building was built in 1907.")),
        )
        assertEquals(ClaimStatus.ESTABLISHED, r.claim("c2")!!.status)
        assertEquals(emptyList<String>(), material(r))
        val everything = (r.answer.lead.map { it.text } + r.answer.caveats + r.answer.sections.flatMap { it.items }.map { it.text }).joinToString(" ")
        assertTrue("omitted, not shown with a disclaimer", "1907" !in everything)
    }

    @Test fun backgroundInTheMainAnswerOfANarrowFactIsOmittedToo() {
        val r = run(
            "When does the city museum open?",
            s("## ANSWER\nThe city museum opens at 09:00 [c1]. Its building dates from 1907 [c2].\n## CLAIM c1 · type=fact · load=yes · scope=city museum opening time · asof=- · volatile=no · needs=direct · needs_input=no\nThe city museum opens at 09:00.\nEVIDENCE: (kind=primary · origin=the city museum) "),
            s("Open daily from 09:00.", cite("https://www.citymuseum.example/visit", "Visiting hours: open daily from 09:00.")),
            s("\n## CLAIM c2 · type=fact · load=no · scope=city museum building · asof=- · volatile=no · needs=direct · needs_input=no\nThe museum building dates from 1907.\nEVIDENCE: (kind=primary · origin=the city museum) "),
            s("Built in 1907.", cite("https://www.citymuseum.example/history", "The building was built in 1907.")),
        )
        assertEquals("The city museum opens at 09:00.", r.answer.lead.joinToString(" ") { it.text })
    }

    @Test fun aRelevantExceptionToTheAnswerStaysVisible() {
        val r = run(
            "When does the city museum open?",
            *hours,
            s("\n## WORTH_KNOWING · bears=exception · on=c1\nOn public holidays it opens only at 12:00 [c2].\n## CLAIM c2 · type=fact · load=no · scope=city museum holiday opening · asof=- · volatile=no · needs=direct · needs_input=no\nOn public holidays the museum opens at 12:00.\nEVIDENCE: (kind=primary · origin=the city museum) "),
            s("Public holidays: from 12:00.", cite("https://www.citymuseum.example/visit", "Public holidays: open from 12:00 only.")),
        )
        assertEquals(listOf("On public holidays it opens only at 12:00."), material(r))
    }

    @Test fun aMaterialSafetyWarningIsKeptEvenForANarrowFact() {
        val r = run(
            "What is the usual adult dose of ibuprofen?",
            s("## ANSWER\nThe usual adult dose is 200 to 400 mg every 4 to 6 hours [c1].\n## CLAIM c1 · type=fact · load=yes · scope=adult ibuprofen dose · asof=- · volatile=no · needs=direct · needs_input=no\nThe usual adult dose is 200 to 400 mg every 4 to 6 hours.\nEVIDENCE: (kind=primary · origin=the national medicines agency) "),
            s("200 to 400 mg every 4 to 6 hours.", cite("https://www.medicines.example/ibuprofen", "Adults: 200 to 400 mg every 4 to 6 hours as needed.")),
            s("\n## WORTH_KNOWING · bears=risk · on=c1\nDo not take more than 1,200 mg in 24 hours without a doctor's advice [c2].\n## CLAIM c2 · type=fact · load=no · scope=adult ibuprofen maximum · asof=- · volatile=no · needs=direct · needs_input=no\nAdults should not take more than 1,200 mg in 24 hours without medical advice.\nEVIDENCE: (kind=primary · origin=the national medicines agency) "),
            s("Do not exceed 1,200 mg in 24 hours.", cite("https://www.medicines.example/ibuprofen", "Do not take more than 1,200 mg in 24 hours unless a doctor tells you to.")),
        )
        assertEquals(listOf("Do not take more than 1,200 mg in 24 hours without a doctor's advice."), material(r))
    }

    @Test fun anItemWithNoStatedBearingOrNoTieToTheAnswerIsOmitted() {
        val r = run(
            "When does the city museum open?",
            *hours,
            s("\n## WORTH_KNOWING · bears=limitation\nThe museum café is not open on Mondays [c2].\n## CLAIM c2 · type=fact · load=no · scope=city museum café · asof=- · volatile=no · needs=direct · needs_input=no\nThe café is not open on Mondays.\nEVIDENCE: (kind=primary · origin=the city museum) "),
            s("Café closed Mondays.", cite("https://www.citymuseum.example/cafe", "The café is closed on Mondays.")),
        )
        assertEquals("a limitation of nothing the answer states", emptyList<String>(), material(r))
    }
}
