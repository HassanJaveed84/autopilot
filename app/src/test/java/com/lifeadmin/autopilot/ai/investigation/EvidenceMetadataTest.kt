package com.lifeadmin.autopilot.ai.investigation

import com.lifeadmin.autopilot.ai.research.RawCitation
import com.lifeadmin.autopilot.ai.research.RawSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * N1 (`docs/live-eval/R-N-a-2026-10-24-rerun.md`): the leading `(kind=… · origin=… · asof=… · about=…)` block of an
 * EVIDENCE or AGAINST line is read with balanced parentheses, or is conservatively rejected. A block that cannot be
 * read never lets its evidence count as support, an unreadable scope label never makes off-scope evidence support
 * a claim, and contract syntax never reaches the screen.
 */
class EvidenceMetadataTest {
    private val now = LocalDate.of(2026, 10, 24).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun cite(url: String, text: String) = RawCitation(url, null, text)
    private fun s(text: String, c: RawCitation? = null) = RawSegment(text, listOfNotNull(c))

    private fun run(question: String, vararg reply: RawSegment, consequential: Boolean = false): TurnResult {
        val objective = ObjectivePreparer.local(question).copy(consequential = consequential)
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false, consequential = consequential)
        work.merge(InvestigationParser.parse(reply.toList(), emptyList(), registry, "t1", now), now)
        work.verifyRan = true
        return work.result(objective, TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, now)
    }

    private fun shown(r: TurnResult): List<String> = r.claims.flatMap { c -> (c.support + c.against).mapNotNull { EvidenceDisplay.summary(it) } }

    /** A children's-dose claim whose only source is labelled as being about adults. */
    private fun dose(attrs: String): TurnResult = run(
        "What is the maximum daily dose of Brand X ibuprofen 200 mg for children aged 6 to 11?",
        s("## ANSWER\nChildren aged 6 to 11 may take up to four tablets a day [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Brand X ibuprofen 200 mg, children aged 6 to 11 · asof=2026 · volatile=no · needs=direct · needs_input=no\nChildren aged 6 to 11 may take up to four Brand X 200 mg tablets in 24 hours.\nEVIDENCE: $attrs"),
        s("Do not take more than 4 tablets in 24 hours.", cite("https://www.brandx.example/ibuprofen-200/label", "Do not take more than 4 tablets in 24 hours.")),
        consequential = true,
    )

    // ------------------------------------------------------------------ the live line

    @Test fun theRerunsEvidenceLabelsAreReadDespiteParenthesesInTheirValues() {
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-rerun.replies.json")
        val at = replay.ranAt.toEpochMilli()
        val registry = SourceRegistry(turnId = "t1")
        val work = TurnWork("t1", emptyList(), taskLinked = false)
        val x = replay.exchanges.single()
        work.merge(InvestigationParser.parse(x.reply.segments, x.reply.searches, registry, "t1", at), at)
        work.verifyRan = true
        val r = work.result(ObjectivePreparer.local(replay.question), TurnEnding.COMPLETE, registry.sources(), emptyList(), emptyList(), null, at)
        val c1 = r.claim("c1")!!
        assertEquals(listOf(EvidenceKind.PRIMARY, EvidenceKind.PRIMARY, EvidenceKind.SECONDARY), c1.support.map { it.kind })
        assertEquals("Union Station D.C. management (Union Station Redevelopment Corp.)", c1.support[0].origin)
        assertEquals("Washington, DC's official tourism organisation (Destination DC) listing", c1.support[2].origin)
        assertTrue("no page is marked as off-scope", c1.support.all { it.scopeNote.isBlank() })
        assertEquals(ClaimStatus.ESTABLISHED, c1.status)
        // The provider's citations and verbatim cited text are untouched.
        val cited = x.reply.segments.flatMap { it.citations }
        for (e in c1.support) assertTrue(e.quote!!, cited.any { it.citedText.trim().startsWith(e.quote!!.take(40)) })
        for (t in shown(r)) {
            assertEquals(t, emptyList<String>(), LeakageScan.scan(t))
            assertFalse(t, t.contains("kind=") || t.contains("origin=") || t.contains("asof="))
        }
    }

    // ------------------------------------------------------------------ the safety rule: a scope label is never lost silently

    @Test fun aParenthesisedScopeLabelStillMakesTheEvidenceOffScope() {
        val r = dose("(kind=primary · about=adults (18 and over) · asof=2026) ")
        assertEquals("the label is about adults: it does not support a children's dose", ClaimStatus.NOT_ESTABLISHED, r.claim("c1")!!.status)
        assertNotEquals(ResultState.ANSWERED, r.state)
    }

    @Test fun anUnclosedLabelBlockIsNeverReadAsInScopeSupport() {
        val r = dose("(kind=primary · about=adults (18 and over) · asof=2026 ")
        assertNotEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        assertTrue("the citation is kept for inspection", r.claim("c1")!!.support.isNotEmpty())
        for (t in shown(r)) assertFalse(t, t.contains("about=") || t.contains("kind="))
    }

    @Test fun aMisspelledOrUnreadableLabelIsNotSilentlyIgnored() {
        assertNotEquals(ClaimStatus.ESTABLISHED, dose("(kind=primary · abuot=adults) ").claim("c1")!!.status)
        assertNotEquals(ClaimStatus.ESTABLISHED, dose("(kind=primary · about adults only) ").claim("c1")!!.status)
        assertNotEquals(ClaimStatus.ESTABLISHED, dose("(kind=primary · about=adults · about=children) ").claim("c1")!!.status)
    }

    @Test fun labelsWrittenAfterTheTextAreNotIgnoredEither() {
        val r = dose("The label is for adults. (kind=primary · about=adults) ")
        assertNotEquals(ClaimStatus.ESTABLISHED, r.claim("c1")!!.status)
        for (t in shown(r)) assertFalse(t, t.contains("about="))
    }

    // ------------------------------------------------------------------ the parser itself

    private fun split(line: String, scope: String = "children aged 6 to 11") = EvidenceAttrs.split(line, scope)

    @Test fun balancedParenthesesCommasWhitespaceAndOptionalFieldsAreRead() {
        val (a, x) = split(" (kind=primary · origin=Washington, DC's tourism office (Destination DC) · asof=2026-05 · about=adults (18 and over) · ) The page says so.")
        assertEquals(EvidenceKind.PRIMARY, a.kind)
        assertEquals("Washington, DC's tourism office (Destination DC)", a.origin)
        assertEquals("2026-05", a.asOf)
        assertEquals("About adults (18 and over)", a.scopeNote)
        assertFalse(a.unreadable)
        assertEquals("The page says so.", x)
        val (b, y) = split("(  kind = review ;asof=2024  )   Pooled trials agree.")
        assertEquals(EvidenceKind.REVIEW, b.kind)
        assertEquals("2024", b.asOf)
        assertEquals("Pooled trials agree.", y)
        val (c, _) = split("(kind=secondary) Text.")
        assertEquals(EvidenceKind.SECONDARY, c.kind)
        assertEquals(null, c.origin)
        assertEquals("", c.scopeNote)
        val (d, _) = split("(scope=adults · kind=primary) Text.")
        assertEquals("scope= is read as about=", "About adults", d.scopeNote)
    }

    @Test fun aLeadingParenthesisWithoutLabelsIsOrdinaryText() {
        val (a, x) = split("(Updated 2025) The fee is $5.")
        assertFalse(a.unreadable)
        assertEquals(null, a.kind)
        assertEquals("(Updated 2025) The fee is $5.", x)
    }

    @Test fun anyBlockThatCannotBeReadSafelyIsRejectedAndItsLabelsAreNotShown() {
        val cases = listOf(
            "(kind=primary · about=adults (18 and over) · asof=2026 The page says so.",
            "(kind=primary · abuot=adults) The page says so.",
            "(kind=primary · about adults only) The page says so.",
            "(kind=primary · about=adults · about=children) The page says so.",
            "(kind=primary) The page says so. (about=adults)",
            "The page says so. (kind=primary · about=adults)",
            "The page says so. kind=primary about=adults",
        )
        for (line in cases) {
            val (a, x) = split(line)
            assertTrue(line, a.unreadable)
            assertEquals(line, null, a.kind)
            assertFalse("$line -> $x", Regex("""(?i)\b(kind|about|origin|asof|scope)\s*=""").containsMatchIn(x))
        }
    }

    @Test fun anUnreadableBlockKeepsTheCitationAndSaysWhyItDoesNotCount() {
        val r = dose("(kind=primary · about=adults (18 and over) · asof=2026 ")
        val c1 = r.claim("c1")!!
        val e = c1.support.single()
        assertTrue(e.labelsUnreadable)
        assertEquals("the provider's verbatim text is kept", "Do not take more than 4 tablets in 24 hours.", e.quote)
        assertEquals(ClaimStatus.NOT_ESTABLISHED, c1.status)
        assertTrue(c1.statusReason, c1.statusReason.contains("labels that could not be read"))
        assertTrue(EvidenceDisplay.note(e)!!.contains("not counted"))
        assertTrue(EvidenceDisplay.summary(e)!!.contains("not shown"))
    }

    @Test fun opposingEvidenceWithUnreadableLabelsStillCountsAgainst() {
        val r = run(
            "Is the Riverside library open on Sundays?",
            s("## ANSWER\nIt opens on Sundays [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Riverside library, Sunday hours · asof=- · volatile=no · needs=direct · needs_input=no\nThe Riverside library opens on Sundays.\nEVIDENCE: (kind=primary) "),
            s("Open Sundays 12:00–17:00.", cite("https://www.riverside-library.example/hours", "Open Sundays 12:00–17:00.")),
            s("\nAGAINST: (kind=secondary · about=the summer timetable (June–August) "),
            s("Closed on Sundays.", cite("https://www.cityguide.example/library", "Closed on Sundays.")),
        )
        assertEquals("an unreadable label can only make a claim weaker", ClaimStatus.CONTESTED, r.claim("c1")!!.status)
    }

    @Test fun theSameSafetyHoldsInOtherDomains() {
        // A procedure for another country, and a price for another size, each with the scope label left unclosed.
        val visa = run(
            "What documents does a UK citizen need for a Schengen visa?",
            s("## ANSWER\nA passport and one photo [c1].\n## CLAIM c1 · type=fact · load=yes · scope=Schengen visa, UK citizens · asof=2026 · volatile=no · needs=direct · needs_input=no\nUK citizens need a passport and one photo.\nEVIDENCE: (kind=primary · about=US citizens (tourist category) "),
            s("Bring your passport and one photo.", cite("https://www.embassy.example/visa", "Bring your passport and one photo.")),
            consequential = true,
        )
        val price = run(
            "How much is the 50 ml bottle?",
            s("## ANSWER\nIt costs $95 [c1].\n## CLAIM c1 · type=fact · load=yes · scope=50 ml bottle · asof=2026-10 · volatile=yes · needs=direct · needs_input=no\nThe 50 ml bottle costs $95.\nEVIDENCE: (kind=primary · about=the 100 ml bottle (gift set) · asof=2026-10 "),
            s("Now $95, October 2026.", cite("https://www.shop.example/bottle", "Now $95, October 2026.")),
        )
        assertNotEquals(ClaimStatus.ESTABLISHED, visa.claim("c1")!!.status)
        assertNotEquals(ClaimStatus.ESTABLISHED, price.claim("c1")!!.status)
    }

    @Test fun theRerunsDatesAreKeptAsTheRepliesNotThePages() {
        val replay = LiveReplay.load("fixtures/live/R-N-a-2026-10-24-rerun.replies.json")
        val at = replay.ranAt.toEpochMilli()
        val registry = SourceRegistry(turnId = "t1")
        val out = InvestigationParser.parse(replay.exchanges.single().reply.segments, emptyList(), registry, "t1", at)
        val support = out.claims.single { it.id == "c1" }.support
        assertEquals(listOf("2026", "2025", null), support.map { it.asOf })
        assertEquals(listOf(DateBasis.STATED, DateBasis.STATED, null), support.map { it.asOfBasis })
        assertEquals("2026 (page about 168 days old at retrieval)", support[0].asOfNote)
        assertTrue(EvidenceDisplay.note(support[0])!!.contains("does not show this date"))
    }
}
