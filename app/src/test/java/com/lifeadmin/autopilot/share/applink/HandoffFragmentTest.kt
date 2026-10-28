package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.share.applink.HandoffProblem.AMBIGUOUS_PLUS
import com.lifeadmin.autopilot.share.applink.HandoffProblem.BAD_ENCODING
import com.lifeadmin.autopilot.share.applink.HandoffProblem.DISALLOWED_CHARACTER
import com.lifeadmin.autopilot.share.applink.HandoffProblem.DUPLICATE_FIELD
import com.lifeadmin.autopilot.share.applink.HandoffProblem.EDGE_WHITESPACE
import com.lifeadmin.autopilot.share.applink.HandoffProblem.EMPTY_VALUE
import com.lifeadmin.autopilot.share.applink.HandoffProblem.MALFORMED_FIELD
import com.lifeadmin.autopilot.share.applink.HandoffProblem.MISSING_TASK
import com.lifeadmin.autopilot.share.applink.HandoffProblem.NO_ITEMS
import com.lifeadmin.autopilot.share.applink.HandoffProblem.TOO_MANY_ITEMS
import com.lifeadmin.autopilot.share.applink.HandoffProblem.UNKNOWN_FIELD
import com.lifeadmin.autopilot.share.applink.HandoffProblem.UNSUPPORTED_VERSION
import com.lifeadmin.autopilot.share.applink.HandoffProblem.VALUE_TOO_LONG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strict `ap=v1` fragment, end to end through the `/autopilot/import` route where it applies. */
class HandoffFragmentTest {
    private val host = "links.example.org"
    private val parser = ImportLinkParser(host)
    private val base = "https://$host/autopilot/import#"

    /** The fixed example of the structured handoff (docs/CHATGPT_APP_LINK_HANDOFF.md). */
    private val example = LinkHandoff(
        task = "Choose a coffee grinder",
        decisions = listOf("Choose Model A"),
        steps = listOf("Check warranty", "Compare retailer prices"),
        notes = listOf("Budget is \$1,000"),
    )

    private fun link(fragment: String) = parser.parse(base + fragment)
    private fun valid(fragment: String) = (link(fragment) as ImportLinkResult.Valid).handoff

    /** The parser's own issue, with its field position; the import route reports only its category. */
    private fun refused(fragment: String) = (HandoffFragment.parse(fragment) as HandoffParseResult.Invalid).issue.also {
        assertTrue(fragment, link(fragment) is ImportLinkResult.Refused)
    }

    /** A one-task, one-step handoff whose step is [raw] (still encoded). */
    private fun step(raw: String) = valid("ap=v1&t=T&s=$raw").steps.single()
    private fun stepProblem(raw: String) = refused("ap=v1&t=T&s=$raw").problem

    // 1. The fixed example

    @Test fun theFixedExampleDecodesExactly() {
        val url = "https://$host/autopilot/import#ap=v1&t=Choose%20a%20coffee%20grinder&d=Choose%20Model%20A" +
            "&s=Check%20warranty&s=Compare%20retailer%20prices&n=Budget%20is%20%241%2C000"
        assertEquals(url, example.toUrl(host))
        val h = (parser.parse(url) as ImportLinkResult.Valid).handoff
        assertEquals(example, h)
        assertEquals("Choose a coffee grinder", h.task)
        assertEquals(listOf("Choose Model A"), h.decisions)
        assertEquals(listOf("Check warranty", "Compare retailer prices"), h.steps)
        assertEquals(listOf("Budget is \$1,000"), h.notes)
        assertEquals(6, h.fieldCount)
    }

    @Test fun legalRawPunctuationNeedsNoEncodingAndOrderIsKept() {
        // What a model might write by hand: RFC 3986 allows $ , : ! ' ( ) * ; @ / ? in a fragment.
        val h = valid("ap=v1&t=Choose%20a%20coffee%20grinder&s=Check%20warranty&d=Choose%20Model%20A&n=Budget%20is%20$1,000&s=Compare%20retailer%20prices")
        assertEquals(example, h)
        assertEquals("Ask: who's free (Mon/Tue)? *today*; @9:30!", step("Ask:%20who's%20free%20(Mon/Tue)?%20*today*;%20@9:30!"))
    }

    @Test fun aTrackingQueryIsIgnored() {
        val url = example.toUrl(host).replace("#", "?utm_source=chatgpt.com#")
        assertEquals(ImportLinkResult.Valid(example), parser.parse(url))
    }

    // 2. Spaces, punctuation, ampersands, Unicode and percent encoding

    @Test fun separatorsInsideValuesStayInside() {
        assertEquals("Tom & Jerry", step("Tom%20%26%20Jerry"))
        assertEquals("a=b", step("a%3Db"))
        assertEquals("C++ & C#", step("C%2B%2B%20%26%20C%23"))
        assertEquals("100% sure", step("100%25%20sure"))
        assertEquals("\"quoted\" <tag> {x} [y] |z| `w` \\", step("%22quoted%22%20%3Ctag%3E%20%7Bx%7D%20%5By%5D%20%7Cz%7C%20%60w%60%20%5C"))
    }

    @Test fun valuesAreDecodedExactlyOnce() {
        assertEquals("%20", step("%2520"))
        assertEquals("%41", step("%2541"))
        assertEquals("A", step("%41"))
        assertEquals("ÿ", step("%c3%bf"))
    }

    @Test fun unicodeEncodedOrRawIsTheSame() {
        assertEquals("Café für 2 € — 東京 ☕ 👨‍👩‍👧", step(LinkHandoff("T", emptyList(), listOf("Café für 2 € — 東京 ☕ 👨‍👩‍👧"), emptyList()).toUrl(host).substringAfter("&s=")))
        assertEquals("Café 東京 ☕", step("Caf%C3%A9%20%E6%9D%B1%E4%BA%AC%20%E2%98%95"))
        assertEquals("Café 東京 ☕", step("Café%20東京%20☕"))
        assertEquals("👍", step("👍"))
        assertEquals("non\u00A0breaking", step("non%C2%A0breaking"))
    }

    @Test fun encodeAndParseRoundTrip() {
        val samples = listOf(
            "plain", "Budget is \$1,000", "a&b=c+d#e%f", "Ünïcödé ✓", "tab-free; (paren) [brk] {brc}", "50/50?", "'single' \"double\"",
            "日本語のテキスト", "emoji 🎉🧪", "x".repeat(HandoffFragment.MAX_ITEM),
        )
        samples.forEach { s ->
            val h = LinkHandoff("Task $s".take(HandoffFragment.MAX_TASK), listOf(s), listOf(s, s), listOf(s))
            assertEquals(s, ImportLinkResult.Valid(h), parser.parse(h.toUrl(host)))
        }
    }

    @Test fun aRawPlusIsRefusedNotGuessed() {
        assertEquals(AMBIGUOUS_PLUS, stepProblem("Check+warranty"))
        assertEquals("C+", step("C%2B"))
    }

    @Test fun brokenEscapesAndInvalidUtf8AreRefused() {
        // Reaches the fragment parser directly: java.net.URI already refuses these as a whole link.
        listOf("%", "%2", "a%G1", "%%41", "%٣٣").forEach {
            assertEquals(it, BAD_ENCODING, (HandoffFragment.parse("ap=v1&t=T&s=$it") as HandoffParseResult.Invalid).issue.problem)
        }
        assertTrue(parser.parse(base + "ap=v1&t=T&s=a%G1") is ImportLinkResult.Refused)
        // Valid escapes, invalid bytes: lone continuation, truncated sequence, overlong form, surrogate, out of range.
        listOf("%80", "%C3", "caf%C3", "%C0%AF", "%ED%A0%80", "%F4%90%80%80", "%FF").forEach { assertEquals(it, BAD_ENCODING, stepProblem(it)) }
    }

    @Test fun controlAndBidiCharactersAreRefused() {
        listOf("line%0Abreak", "cr%0D", "tab%09x", "nul%00", "del%7F", "c1%C2%85", "rlo%E2%80%AEx", "lri%E2%81%A6x", "ls%E2%80%A8x", "lrm%E2%80%8Ex")
            .forEach { assertEquals(it, DISALLOWED_CHARACTER, stepProblem(it)) }
    }

    @Test fun valuesMustNotBeEmptyOrPadded() {
        assertEquals(EMPTY_VALUE, stepProblem(""))
        assertEquals(EMPTY_VALUE, stepProblem("%20%20"))
        listOf("%20lead", "trail%20", "nbsp%C2%A0").forEach { assertEquals(it, EDGE_WHITESPACE, stepProblem(it)) }
        assertEquals("inner  spaces", step("inner%20%20spaces"))
    }

    // 3. Missing, duplicated, malformed and unknown fields

    @Test fun theVersionComesFirstAndOnlyOnce() {
        assertEquals(HandoffIssue(UNSUPPORTED_VERSION, 1, 3), refused("ap=v2&t=T&s=S"))
        assertEquals(UNSUPPORTED_VERSION, refused("ap=V1&t=T&s=S").problem)
        assertEquals(UNSUPPORTED_VERSION, refused("ap=&t=T&s=S").problem)
        assertEquals(UNSUPPORTED_VERSION, refused("ap=v1x&t=T").problem)
        assertEquals(HandoffIssue(DUPLICATE_FIELD, 3, 4), refused("ap=v1&t=T&ap=v1&s=S"))
        // Not starting with ap= at all is not a handoff.
        assertEquals(ImportLinkResult.Refused(ImportRefusal.NO_HANDOFF), link("t=T&ap=v1&s=S"))
    }

    @Test fun theTaskIsRequiredOnceAndSomethingMustFollowIt() {
        assertEquals(HandoffIssue(MISSING_TASK, null, 3), refused("ap=v1&d=D&s=S"))
        assertEquals(HandoffIssue(DUPLICATE_FIELD, 3, 4), refused("ap=v1&t=A&t=B&s=S"))
        assertEquals(HandoffIssue(NO_ITEMS, null, 2), refused("ap=v1&t=T"))
        assertEquals(MISSING_TASK, refused("ap=v1").problem)
        // Each kind is optional on its own.
        assertEquals(LinkHandoff("T", listOf("D"), emptyList(), emptyList()), valid("ap=v1&t=T&d=D"))
        assertEquals(LinkHandoff("T", emptyList(), emptyList(), listOf("N")), valid("ap=v1&t=T&n=N"))
    }

    @Test fun malformedPairsAreRefusedWithTheirPosition() {
        assertEquals(HandoffIssue(MALFORMED_FIELD, 3, 3), refused("ap=v1&t=T&s"))
        assertEquals(HandoffIssue(MALFORMED_FIELD, 3, 4), refused("ap=v1&t=T&&s=S"))
        assertEquals(HandoffIssue(MALFORMED_FIELD, 4, 4), refused("ap=v1&t=T&s=S&"))
        assertEquals(HandoffIssue(MALFORMED_FIELD, 3, 3), refused("ap=v1&t=T&=S"))
        assertEquals(DISALLOWED_CHARACTER, stepProblem("a=b"))
        assertEquals(EMPTY_VALUE, refused("ap=v1&t=&s=S").problem)
    }

    @Test fun unknownOrMiscasedKeysAreRefused() {
        listOf("x=1", "S=Step", "step=Step", "d%20=D", "%73=S", "message=hello").forEach {
            assertEquals(it, HandoffIssue(UNKNOWN_FIELD, 4, 4), refused("ap=v1&t=T&s=S&$it"))
        }
    }

    // 4. Length limits

    @Test fun valueLengthsAreCappedInCharactersNotBytes() {
        val max = HandoffFragment.MAX_ITEM
        assertEquals("a".repeat(max), step("a".repeat(max)))
        assertEquals(VALUE_TOO_LONG, stepProblem("a".repeat(max + 1)))
        assertEquals("☕".repeat(max), step("%E2%98%95".repeat(max)))
        assertEquals("👍".repeat(max), step("👍".repeat(max)))
        assertEquals(VALUE_TOO_LONG, stepProblem("👍".repeat(max + 1)))
        val maxTask = HandoffFragment.MAX_TASK
        assertEquals("t".repeat(maxTask), valid("ap=v1&t=${"t".repeat(maxTask)}&s=S").task)
        assertEquals(HandoffIssue(VALUE_TOO_LONG, 2, 3), refused("ap=v1&t=${"t".repeat(maxTask + 1)}&s=S"))
    }

    @Test fun itemCountsAreCapped() {
        fun many(key: String, n: Int) = (1..n).joinToString("") { "&$key=$it" }
        assertEquals(HandoffFragment.MAX_STEPS, valid("ap=v1&t=T" + many("s", HandoffFragment.MAX_STEPS)).steps.size)
        assertEquals(HandoffIssue(TOO_MANY_ITEMS, 13, 13), refused("ap=v1&t=T" + many("s", HandoffFragment.MAX_STEPS + 1)))
        assertEquals(TOO_MANY_ITEMS, refused("ap=v1&t=T" + many("d", HandoffFragment.MAX_DECISIONS + 1)).problem)
        assertEquals(TOO_MANY_ITEMS, refused("ap=v1&t=T" + many("n", HandoffFragment.MAX_NOTES + 1)).problem)
    }

    @Test fun anOversizedLinkIsRefusedBeforeParsing() {
        // Each field within its own cap, but percent-encoded non-ASCII takes nine URL characters per symbol.
        val one = LinkHandoff("T", listOf("☕".repeat(HandoffFragment.MAX_ITEM)), emptyList(), emptyList())
        assertTrue(one.toUrl(host).length < ImportLinkParser.MAX_URL)
        assertEquals(ImportLinkResult.Valid(one), parser.parse(one.toUrl(host)))
        val three = one.copy(decisions = List(3) { one.decisions.single() })
        assertTrue(three.toUrl(host).length > ImportLinkParser.MAX_URL)
        assertEquals(ImportLinkResult.Refused(ImportRefusal.TOO_BIG), parser.parse(three.toUrl(host)))
    }

    // 5. Hosts, paths and schemes

    @Test fun theEnvelopeRulesStillApply() {
        val fragment = example.toUrl(host).substringAfter('#')
        val notALink = ImportLinkResult.Refused(ImportRefusal.NOT_A_LINK)
        assertEquals(notALink, parser.parse("http://$host/autopilot/import#$fragment"))
        assertEquals(notALink, parser.parse("https://evil.example/autopilot/import#$fragment"))
        assertEquals(notALink, parser.parse("https://sub.$host/autopilot/import#$fragment"))
        assertEquals(notALink, parser.parse("https://$host:8443/autopilot/import#$fragment"))
        assertEquals(notALink, parser.parse("https://user@$host/autopilot/import#$fragment"))
        assertEquals(notALink, parser.parse("https://$host/autopilot/test#$fragment"))
        assertEquals(notALink, parser.parse("https://$host/autopilot/import#ap=v1&t=Raw space&s=S"))
        assertEquals(notALink, parser.parse("https://$host/autopilot/import#ap=v1&t=T&s=a#b"))
        // Handoff data in the query is not read.
        assertEquals(ImportLinkResult.Refused(ImportRefusal.NO_HANDOFF), parser.parse("https://$host/autopilot/import?$fragment"))
    }
}
