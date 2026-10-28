package com.lifeadmin.autopilot.domain

import com.lifeadmin.autopilot.domain.model.StepDetails
import com.lifeadmin.autopilot.domain.model.StepLink
import com.lifeadmin.autopilot.domain.model.WebLink
import com.lifeadmin.autopilot.domain.proposal.MatchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Rich Steps: link validation, tracking-parameter removal, duplicate detection and description merging. */
class StepDetailsTest {
    private fun ok(raw: String, missingScheme: Boolean = false) = (WebLink.check(raw, missingScheme) as WebLink.Checked.Ok).url
    private fun bad(raw: String, missingScheme: Boolean = false) = WebLink.check(raw, missingScheme) is WebLink.Checked.Bad

    @Test fun onlyHttpAndHttpsWebAddressesAreAccepted() {
        assertEquals("https://www.chanel.com/us/fragrance/p/107460/", ok("https://www.chanel.com/us/fragrance/p/107460/"))
        assertEquals("http://example.org/page", ok("http://example.org/page"))
        assertEquals("HTTPS://Example.COM/A", ok("HTTPS://Example.COM/A"))
        listOf(
            "javascript:alert(1)", "intent://scan/#Intent;end", "file:///sdcard/x", "content://x/y", "mailto:a@b.example", "tel:+15551234",
            "ftp://example.com/f", "www.chanel.com/us", "https://localhost/x", "https://bank.example@evil.example/login",
            "https://exa mple.com", "https:// example.com", "https://münchen.de/", "https://.example.com", "",
        ).forEach { assertTrue(it, bad(it)) }
        assertTrue(bad("https://example.com/" + "a".repeat(WebLink.MAX_URL)))
    }

    @Test fun theEditorMayLeaveOutTheSchemeButNeverGuessesAnother() {
        assertEquals("https://www.chanel.com/us", ok("www.chanel.com/us", missingScheme = true))
        assertTrue(bad("javascript:alert(1)", missingScheme = true))
        assertTrue(bad("mailto:a@b.example", missingScheme = true))
    }

    @Test fun onlyRecognisedTrackingParametersAreRemovedAndVariantsKeepTheirExactBytes() {
        assertEquals(
            "https://shop.example.com/p/123?size=50ml&color=Midnight%20Blue",
            ok("https://shop.example.com/p/123?utm_source=chatgpt.com&size=50ml&gclid=abc&color=Midnight%20Blue&utm_campaign=x"),
        )
        assertEquals("https://www.chanel.com/us/p/107460/", ok("https://www.chanel.com/us/p/107460/?utm_source=chatgpt.com"))
        // A fragment stays; a parameter that merely contains a tracking word stays too.
        assertEquals("https://example.com/a?ref=home&my_utm=1#specs", ok("https://example.com/a?ref=home&fbclid=Z&my_utm=1#specs"))
        assertEquals("https://example.com/search?q=utm_source", ok("https://example.com/search?q=utm_source"))
    }

    @Test fun samePageKeysIgnoreSchemeWwwSlashFragmentAndTrackingButNotVariants() {
        val a = WebLink.sameKey("https://www.travel.state.gov/content/photos.html/")
        assertEquals(a, WebLink.sameKey("http://travel.state.gov/content/photos.html?utm_source=chatgpt.com#top"))
        assertEquals(a, WebLink.sameKey("https://TRAVEL.state.gov:443/content/photos.html"))
        assertTrue(WebLink.sameKey("https://shop.example/p?size=50ml") != WebLink.sameKey("https://shop.example/p?size=100ml"))
    }

    @Test fun theDomainIsShownWithoutWww() {
        assertEquals("chanel.com", StepLink("https://www.chanel.com/us/").host)
        assertEquals("travel.state.gov", StepLink("https://travel.state.gov/x").host)
        assertEquals("Product page", StepLink("https://www.chanel.com/", "Product page").title)
        assertEquals("chanel.com", StepLink("https://www.chanel.com/").title)
    }

    @Test fun linksAreCleanedDeduplicatedAndCapped() {
        val cleaned = StepDetails.cleanLinks(
            listOf(
                StepLink("https://www.chanel.com/p/1/", "  Product   page "),
                StepLink("https://chanel.com/p/1?utm_source=x", "Duplicate"),
                StepLink("https://www.nordstrom.com/s/1", ""),
            ),
        )
        assertEquals(listOf(StepLink("https://www.chanel.com/p/1/", "Product page"), StepLink("https://www.nordstrom.com/s/1", null)), cleaned)
        try {
            StepDetails.cleanLinks(List(StepDetails.MAX_LINKS + 1) { StepLink("https://example.com/$it") }); fail("over the cap")
        } catch (_: IllegalArgumentException) {}
        try {
            StepDetails.cleanLinks(listOf(StepLink("https://example.com", "x".repeat(WebLink.MAX_LABEL + 1)))); fail("label too long")
        } catch (_: IllegalArgumentException) {}
        assertEquals(cleaned, StepDetails.decodeLinks(StepDetails.encodeLinks(cleaned)))
        assertNull(StepDetails.encodeLinks(emptyList()))
        assertEquals(emptyList<StepLink>(), StepDetails.decodeLinks(null))
    }

    @Test fun descriptionsArePlainTextWithLineBreaksAndALimit() {
        assertEquals("Line one\nLine two", StepDetails.cleanDescription("  Line one\r\nLine two \n"))
        assertNull(StepDetails.cleanDescription("   \n "))
        try { StepDetails.cleanDescription("x".repeat(StepDetails.MAX_DESCRIPTION + 1)); fail("too long") } catch (_: IllegalArgumentException) {}
    }

    @Test fun mergingAddsOnlyWhatIsNewAndNeverRewritesTheUsersText() {
        val mine = "CVS on Main St does them.\nBring glasses off."
        val added = StepDetails.newParagraphs(mine, listOf("2 x 2 in, white background.", "cvs on main st does them"), MatchKey::of)
        assertEquals(listOf("2 x 2 in, white background."), added)
        assertEquals("CVS on Main St does them.\nBring glasses off.\n\n2 x 2 in, white background.", StepDetails.appendDescription(mine, added))
        assertEquals(mine, StepDetails.appendDescription(mine, emptyList()))
        assertEquals("New", StepDetails.appendDescription(null, listOf("New")))
        val have = listOf(StepLink("https://www.example.gov/photos.html/"))
        assertEquals(
            listOf(StepLink("https://tools.example.com/find")),
            StepDetails.newLinks(have, listOf(StepLink("https://example.gov/photos.html?utm_source=chatgpt.com"), StepLink("https://tools.example.com/find"))),
        )
    }
}
