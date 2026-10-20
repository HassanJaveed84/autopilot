package com.lifeadmin.autopilot.ai.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hardening finding B: a host name never proves ownership; only government domains on label boundaries are Official. */
class SourcesTest {
    private fun tier(url: String) = Sources.tier(url)

    @Test fun recognizedGovernmentDomainsAreOfficial() {
        listOf(
            "https://www.gov.uk/renew-adult-passport", "https://gov.uk/", "https://travel.state.gov/passports", "https://www.nhs.uk/x",
            "https://www.canada.ca/en.html", "https://service.gov.uk/", "https://ec.europa.eu/info", "https://www.gob.es/", "https://www.defense.mil/",
        ).forEach { assertEquals(it, SourceTier.OFFICIAL, tier(it)) }
    }

    @Test fun lookalikesAndDeceptiveHostsAreWebPages() {
        listOf(
            "https://gov.uk.evil.example/",          // suffix smuggled into a subdomain
            "https://www.gov-uk.com/",               // hyphen look-alike
            "https://evilgov.uk/",                   // no label boundary before gov
            "https://notgov.uk/",
            "https://www.state.gov.phish.example/",
            "https://govuk.example/",
            "https://gov/",                          // bare single-label suffix is not a site
            "https://192.0.2.1/gov.uk",
            "https://www.gov.uk@evil.example/",      // userinfo: host is evil.example
        ).forEach { assertEquals(it, SourceTier.WEB, tier(it)) }
    }

    @Test fun operatorAndBrandNamesInAHostAreNotEvidence() {
        listOf(
            "https://www.northlinerail.com/tickets", "https://northline-rail-tickets.example/", "https://booking.northlinerail.co.uk/",
            "https://passport-office.example/", "https://hmrc-refunds.example/", "https://www.reddit.com/r/northlinerail",
        ).forEach { assertEquals(it, SourceTier.WEB, tier(it)) }
    }

    @Test fun urlSafetyProtectionsAreUnchanged() {
        assertNull(Sources.safeExternalUrl("javascript:alert(1)"))
        assertNull(Sources.safeExternalUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(Sources.safeExternalUrl("file:///etc/passwd"))
        assertNull(Sources.safeExternalUrl("https://user:pw@example.com/"))
        assertNull(Sources.safeExternalUrl("https://127.0.0.1/"))
        assertNull(Sources.safeExternalUrl("https://host.internal/"))
        assertNull(Sources.safeExternalUrl("https://[::1]/"))
        assertEquals("https://www.gov.uk/x", Sources.safeExternalUrl(" https://www.gov.uk/x "))
        assertEquals("http://example.com/", Sources.safeExternalUrl("http://example.com/"))
    }
}
