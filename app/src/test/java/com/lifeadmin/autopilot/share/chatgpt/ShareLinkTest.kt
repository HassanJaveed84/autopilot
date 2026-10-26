package com.lifeadmin.autopilot.share.chatgpt

import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.ID
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinkTest {
    @Test fun acceptsTheShareShapeAndCanonicalizes() {
        assertEquals(URL, ShareLink.parse(URL)?.url)
        assertEquals(URL, ShareLink.parse("$URL?ogimg=plain")?.url)
        assertEquals(URL, ShareLink.parse("$URL/")?.url)
        assertEquals(URL, ShareLink.parse("$URL#frag")?.url)
        assertEquals(URL, ShareLink.parse("HTTPS://ChatGPT.com/share/${ID.uppercase()}")?.url)
        assertEquals(URL, ShareLink.parse("https://chatgpt.com:443/share/$ID")?.url)
    }

    @Test fun rejectsOtherHostsSchemesAndPaths() {
        listOf(
            "http://chatgpt.com/share/$ID",
            "https://chat.openai.com/share/$ID",
            "https://www.chatgpt.com/share/$ID",
            "https://chatgpt.com.evil.example/share/$ID",
            "https://evil.example/share/$ID",
            "https://evil.example/?u=https://chatgpt.com/share/$ID",
            "https://chatgpt.com@evil.example/share/$ID",
            "https://user@chatgpt.com/share/$ID",
            "https://chatgpt.com:8443/share/$ID",
            "https://chatgpt.com/share/$ID/continue",
            "https://chatgpt.com/c/$ID",
            "https://chatgpt.com/share/not-an-id",
            "https://chatgpt.com/share/%2e%2e/$ID",
            "https://chatgpt.com/share/../share/$ID",
            "https://chatgpt.com./share/$ID",
            "javascript:alert(1)",
            "file:///share/$ID",
            "",
        ).forEach { assertNull(it, ShareLink.parse(it)) }
    }

    @Test fun findsTheLinkInSharedText() {
        assertEquals(SharedText.Found(ShareLink.parse(URL)!!), SharedTextParser.parse(URL))
        assertEquals(SharedText.Found(ShareLink.parse(URL)!!), SharedTextParser.parse("Widget decision\n$URL?ogimg=plain"))
        assertEquals(SharedText.Found(ShareLink.parse(URL)!!), SharedTextParser.parse("See ($URL)."))
        assertEquals(SharedText.Found(ShareLink.parse(URL)!!), SharedTextParser.parse("https://example.com/x and $URL"))
    }

    @Test fun reportsTextWithoutAShareLink() {
        assertEquals(SharedText.NoLink, SharedTextParser.parse(null))
        assertEquals(SharedText.NoLink, SharedTextParser.parse("   "))
        assertEquals(SharedText.NoLink, SharedTextParser.parse("Buy milk tomorrow"))
        assertEquals(SharedText.OtherLink, SharedTextParser.parse("https://evil.example/share/$ID"))
        assertEquals(SharedText.OtherLink, SharedTextParser.parse("http://chatgpt.com/share/$ID"))
    }
}
