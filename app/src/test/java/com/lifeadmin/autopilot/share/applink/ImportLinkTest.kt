package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.share.applink.HandoffProblem.DUPLICATE_FIELD
import com.lifeadmin.autopilot.share.applink.HandoffProblem.VALUE_TOO_LONG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The round trip's URL contract: the optional `r` reference and the `/autopilot/import` route. */
class ImportLinkTest {
    private val host = "links.example.org"
    private val parser = ImportLinkParser(host)
    private val base = "https://$host/autopilot/import#"
    private val ref = "3f2a9c1e-7b4d-4c8e-9a1f-0d2b6e5c7a90"

    private fun valid(fragment: String) = (parser.parse(base + fragment) as ImportLinkResult.Valid).handoff
    private fun refused(url: String) = (parser.parse(url) as ImportLinkResult.Refused).reason

    @Test fun theReferenceIsOptionalAndCopiedExactly() {
        val h = valid("ap=v1&r=$ref&t=Choose%20a%20coffee%20grinder&d=Choose%20Model%20A&s=Check%20warranty&n=Budget%20is%20%241%2C000")
        assertEquals(ref, h.ref)
        assertEquals("Choose a coffee grinder", h.task)
        assertEquals(listOf("Choose Model A"), h.decisions)
        assertEquals(listOf("Check warranty"), h.steps)
        assertEquals(listOf("Budget is \$1,000"), h.notes)
        // Anywhere after the version, at most once; a link without it is the original v1 and still valid.
        assertEquals(ref, valid("ap=v1&t=T&s=S&r=$ref").ref)
        assertNull(valid("ap=v1&t=T&s=S").ref)
    }

    @Test fun aRepeatedOrOversizedReferenceIsRefused() {
        fun issue(fragment: String) = (HandoffFragment.parse(fragment) as HandoffParseResult.Invalid).issue
        assertEquals(HandoffIssue(DUPLICATE_FIELD, 4, 5), issue("ap=v1&r=a&t=T&r=b&s=S"))
        assertEquals(VALUE_TOO_LONG, issue("ap=v1&r=${"a".repeat(HandoffFragment.MAX_REF + 1)}&t=T&s=S").problem)
        assertEquals(ImportRefusal.MALFORMED, refused(base + "ap=v1&r=a&t=T&r=b&s=S"))
        assertEquals(4, valid("ap=v1&r=$ref&t=T&s=S").fieldCount)
    }

    @Test fun refusalsAreCategorisedNotEchoed() {
        assertEquals(ImportRefusal.NOT_A_LINK, refused("https://evil.example/autopilot/import#ap=v1&t=T&s=S"))
        assertEquals(ImportRefusal.NOT_A_LINK, refused("http://$host/autopilot/import#ap=v1&t=T&s=S"))
        // The retired display-only spike route is not accepted here (and no longer exists).
        assertEquals(ImportRefusal.NOT_A_LINK, refused("https://$host/autopilot/test#ap=v1&t=T&s=S"))
        assertEquals(ImportRefusal.NOT_A_LINK, refused("https://$host/autopilot/import/x#ap=v1&t=T&s=S"))
        assertEquals(ImportRefusal.NO_HANDOFF, refused("https://$host/autopilot/import"))
        assertEquals(ImportRefusal.NO_HANDOFF, refused("https://$host/autopilot/import#message=hello-from-chatgpt"))
        assertEquals(ImportRefusal.NEWER_VERSION, refused(base + "ap=v3&t=T&s=S"))
        assertEquals(ImportRefusal.MALFORMED, refused(base + "ap=v1&t=T&s=a+b"))
        assertEquals(ImportRefusal.MALFORMED, refused(base + "ap=v1&t=T&s=%E2%28"))
        assertEquals(ImportRefusal.MALFORMED, refused(base + "ap=v1&t=T&x=1"))
        assertEquals(ImportRefusal.MALFORMED, refused(base + "ap=v1&s=S"))
        assertEquals(ImportRefusal.TOO_BIG, refused(base + "ap=v1&t=T&s=${"a".repeat(HandoffFragment.MAX_ITEM + 1)}"))
        assertEquals(ImportRefusal.TOO_BIG, refused(base + "ap=v1&t=T" + "&s=S".repeat(HandoffFragment.MAX_STEPS + 1)))
        assertEquals(ImportRefusal.TOO_BIG, refused(base + "ap=v1&t=T&s=" + "%20a".repeat(2_100)))
    }

    @Test fun aQueryIsIgnoredAndALongFullHandoffFits() {
        assertEquals("T", valid("ap=v1&t=T&s=S").task)
        assertTrue(parser.parse("https://$host/autopilot/import?utm_source=chatgpt.com#ap=v1&t=T&s=S") is ImportLinkResult.Valid)
        // 10 steps of 189 characters, percent-encoded: over 2,048 characters, inside the route's 4,096.
        val full = LinkHandoff("T", emptyList(), List(10) { i -> "Step $i " + "x".repeat(180) + " é" }, emptyList(), ref).toUrl(host, ImportLinkParser.PATH)
        assertTrue(full.length in 2_049..ImportLinkParser.MAX_URL)
        assertEquals(10, (parser.parse(full) as ImportLinkResult.Valid).handoff.steps.size)
    }

    @Test fun encodeAndParseRoundTripWithAReference() {
        val h = LinkHandoff("Café & co: 50% off?", listOf("Pick the \"A\" + B"), listOf("Call O'Brien, #2"), listOf("东京 ☕ = yes"), ref)
        assertEquals(h, (parser.parse(h.toUrl(host, ImportLinkParser.PATH)) as ImportLinkResult.Valid).handoff)
    }

    @Test fun thePromptPrefixIsAValidStartAndKeepsLongTitlesWithinTheLimit() {
        val prefix = ImportLinkParser.prefix(host, ref, "Choose a coffee grinder")
        assertEquals("https://$host/autopilot/import#ap=v2&r=$ref&t=Choose%20a%20coffee%20grinder", prefix)
        val h = (parser.parse("$prefix&s=Check%20warranty&end=1") as ImportLinkResult.Valid).handoff
        assertEquals(ref, h.ref)
        val long = "Plan the " + "very ".repeat(60) + "long trip"
        val clipped = ImportLinkParser.linkTitle(long)
        assertTrue(clipped.codePointCount(0, clipped.length) <= HandoffFragment.MAX_TASK)
        assertTrue(clipped.startsWith("Plan the very very"))
        assertTrue(clipped.endsWith("…"))
        assertTrue(parser.parse(ImportLinkParser.prefix(host, ref, long) + "&s=S&end=1") is ImportLinkResult.Valid)
    }

    @Test fun aTaskReferenceIsOnlyAnIdShapedValue() {
        assertTrue(TaskRef.isWellFormed(ref))
        assertTrue(TaskRef.isWellFormed("id-12"))
        listOf(null, "", "a b", "../x", "a".repeat(65), "id%2F1", "é").forEach { assertFalse("$it", TaskRef.isWellFormed(it)) }
    }
}
