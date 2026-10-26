package com.lifeadmin.autopilot.share.applink

import com.lifeadmin.autopilot.share.applink.TestLinkProblem.INVALID_MESSAGE
import com.lifeadmin.autopilot.share.applink.TestLinkProblem.MALFORMED
import com.lifeadmin.autopilot.share.applink.TestLinkProblem.NOT_HTTPS
import com.lifeadmin.autopilot.share.applink.TestLinkProblem.NO_MESSAGE
import com.lifeadmin.autopilot.share.applink.TestLinkProblem.WRONG_HOST
import com.lifeadmin.autopilot.share.applink.TestLinkProblem.WRONG_PATH
import org.junit.Assert.assertEquals
import org.junit.Test

class TestLinkParserTest {
    private val host = "links.example.org"
    private val parser = TestLinkParser(host)
    private fun rejected(p: TestLinkProblem) = TestLinkResult.Rejected(p)

    @Test fun acceptsTheFixedTestLink() {
        assertEquals("https://links.example.org/autopilot/test#message=hello-from-chatgpt", TestLinkParser.testUrl(host))
        assertEquals(TestLinkResult.Received("hello-from-chatgpt"), parser.parse(TestLinkParser.testUrl(host)))
        assertEquals(TestLinkResult.Received("A.b_c~9"), parser.parse("https://$host/autopilot/test#message=A.b_c~9"))
        assertEquals(TestLinkResult.Received("x"), parser.parse("HTTPS://LINKS.Example.ORG:443/autopilot/test#message=x"))
    }

    @Test fun aQueryIsIgnoredNeverRead() {
        assertEquals(TestLinkResult.Received("hello"), parser.parse("https://$host/autopilot/test?utm_source=chatgpt.com#message=hello"))
        assertEquals(rejected(NO_MESSAGE), parser.parse("https://$host/autopilot/test?message=hello"))
    }

    @Test fun rejectsOtherSchemesHostsAndPorts() {
        assertEquals(rejected(NOT_HTTPS), parser.parse("http://$host/autopilot/test#message=hello"))
        assertEquals(rejected(NOT_HTTPS), parser.parse("intent://$host/autopilot/test#message=hello"))
        assertEquals(rejected(NOT_HTTPS), parser.parse("javascript:alert(1)//#message=hello"))
        listOf(
            "https://evil.example/autopilot/test#message=hello",
            "https://$host.evil.example/autopilot/test#message=hello",
            "https://sub.$host/autopilot/test#message=hello",
            "https://links-example.org/autopilot/test#message=hello",
            "https://user@$host/autopilot/test#message=hello",
            "https://$host:8443/autopilot/test#message=hello",
            "https:///autopilot/test#message=hello",
        ).forEach { assertEquals(it, rejected(WRONG_HOST), parser.parse(it)) }
    }

    @Test fun rejectsEveryOtherPath() {
        listOf("", "/", "/autopilot", "/autopilot/test/", "/autopilot/test/x", "/autopilot/Test", "/autopilot/%74est", "/x/../autopilot/test", "/autopilot//test", "/autopilot/test;x")
            .forEach { assertEquals(it, rejected(WRONG_PATH), parser.parse("https://$host$it#message=hello")) }
    }

    @Test fun theMessageHasExactlyOneStrictForm() {
        assertEquals(rejected(NO_MESSAGE), parser.parse("https://$host/autopilot/test"))
        assertEquals(rejected(NO_MESSAGE), parser.parse("https://$host/autopilot/test#"))
        assertEquals(rejected(NO_MESSAGE), parser.parse("https://$host/autopilot/test#msg=hello"))
        listOf("message=", "message=hello%20there", "message=a&b=c", "message=hello=x", "message=" + "a".repeat(65), "message=héllo")
            .forEach { assertEquals(it, rejected(INVALID_MESSAGE), parser.parse("https://$host/autopilot/test#$it")) }
        assertEquals(TestLinkResult.Received("a".repeat(64)), parser.parse("https://$host/autopilot/test#message=" + "a".repeat(64)))
    }

    @Test fun malformedOrOversizedInputIsRefused() {
        assertEquals(rejected(MALFORMED), parser.parse(null))
        assertEquals(rejected(MALFORMED), parser.parse("https://$host/autopilot/test#message=a#b"))
        assertEquals(rejected(MALFORMED), parser.parse("https://$host/autopilot/test#message=<b>"))
        assertEquals(rejected(MALFORMED), parser.parse("https://$host/autopilot test#message=a"))
        assertEquals(rejected(MALFORMED), parser.parse("https://$host/autopilot/test?" + "q".repeat(2_100) + "#message=a"))
    }
}
