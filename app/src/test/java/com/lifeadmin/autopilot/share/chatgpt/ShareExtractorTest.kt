package com.lifeadmin.autopilot.share.chatgpt

import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.assistant
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.dataPage
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.page
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.sampleConversation
import com.lifeadmin.autopilot.share.chatgpt.SharePageFixtures.user
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareExtractorTest {
    private fun success(html: String): SharedConversation {
        val e = ShareExtractor.extract(html)
        assertTrue("$e", e is Extraction.Success)
        return (e as Extraction.Success).conversation
    }

    @Test fun extractsVisibleTextAndTheSharedResponse() {
        val c = success(dataPage(sampleConversation))
        assertEquals("Synthetic decision", c.title)
        assertEquals(
            listOf(
                SharedMessage("m-u1", Role.USER, "Help me pick a synthetic widget."),
                // Citation markers are private-use characters, not text.
                SharedMessage("m-a1", Role.ASSISTANT, "Widget A is the better pick ."),
                SharedMessage("m-u2", Role.USER, "Prepare it for Autopilot."),
                SharedMessage("m-a2", Role.ASSISTANT, "Here is your handoff."),
            ),
            c.messages,
        )
        assertEquals("m-a2", c.target.id)
        assertEquals(TargetChoice.HIGHLIGHTED, c.targetChoice)
    }

    @Test fun anEarlierHighlightedResponseIsTheTarget() {
        val c = success(dataPage(sampleConversation, highlighted = "m-a1"))
        assertEquals("m-a1", c.target.id)
        assertEquals(TargetChoice.HIGHLIGHTED, c.targetChoice)
        assertEquals(4, c.messages.size)
    }

    @Test fun withoutAUsableHighlightTheLatestResponseIsTheTarget() {
        listOf(null, "m-u2", "m-hidden", "unknown").forEach { h ->
            val c = success(dataPage(sampleConversation, highlighted = h))
            assertEquals("$h", "m-a2", c.target.id)
            assertEquals(TargetChoice.LATEST, c.targetChoice)
        }
    }

    @Test fun fallsBackToTheMappingPath() {
        val c = success(dataPage(sampleConversation, includeLinear = false))
        assertEquals(listOf("m-u1", "m-a1", "m-u2", "m-a2"), c.messages.map { it.id })
    }

    @Test fun anErrorResponseIsUnavailable() {
        assertEquals(Extraction.Unavailable, ShareExtractor.extract(SharePageFixtures.errorPage()))
    }

    @Test fun metadataAloneIsNotAConversation() {
        val metaOnly = """<html><head><meta property="og:title" content="Synthetic"><meta property="og:description" content="Looks like a chat"></head><body><p>Synthetic</p></body></html>"""
        assertTrue(ShareExtractor.extract(metaOnly) is Extraction.Unrecognized)
        assertTrue(ShareExtractor.extract("") is Extraction.Unrecognized)
    }

    @Test fun aConversationWithoutAnAssistantResponseIsUnrecognized() {
        assertEquals(Extraction.Unrecognized("no assistant response"), ShareExtractor.extract(dataPage(listOf(user("u", "Only a question")))))
    }

    @Test fun malformedLoaderDataIsUnrecognized() {
        listOf(
            """<script>window.__reactRouterContext.streamController.enqueue("not json");</script>""",
            """<script>window.__reactRouterContext.streamController.enqueue("[1,2");</script>""",
            """<script>window.__reactRouterContext.streamController.enqueue("[{\"_1\":2},\"other\",3]");</script>""",
            """<script>window.__reactRouterContext.streamController.enqueue("unterminated""",
            page("""[{"_1":2},"loaderData",{"_3":4},"routes/share.x",{"_5":6},"serverResponse",{"_7":8},"type","weird"]"""),
        ).forEach { html -> assertTrue(html, ShareExtractor.extract(html) is Extraction.Unrecognized) }
    }

    /** A table written by hand in the observed encoding, so the decoder is not only checked against the test encoder. */
    @Test fun readsAHandWrittenTurboStreamTable() {
        val table = """[{"_1":2,"_15":-5,"_16":-5},"loaderData",{"_3":4},"routes/share.${'$'}shareId.(${'$'}action)",{"_5":6},""" +
            """"serverResponse",{"_7":8,"_9":10},"type","data","data",{"_11":12,"_13":14},"title","T","linear_conversation",[17,18],""" +
            """"actionData","errors",{"_19":-5},{"_19":20},"message",{"_21":22,"_23":24,"_25":26,"_27":28,"_29":30},""" +
            """"id","msg-1","author",{"_31":32},"content",{"_33":34,"_35":36},"recipient","all","channel","final","role","assistant",""" +
            """"content_type","text","parts",[37],"Synthetic answer"]"""
        val c = success(page(table))
        assertEquals("T", c.title)
        assertEquals(listOf(SharedMessage("msg-1", Role.ASSISTANT, "Synthetic answer")), c.messages)
        assertEquals(TargetChoice.LATEST, c.targetChoice)
    }

    @Test fun escapedQuotesInsideTheLiteralAreHandled() {
        val c = success(dataPage(listOf(user("u", "Say \"hi\""), assistant("a", "He said \"hi\" and \\ left"))))
        assertEquals("He said \"hi\" and \\ left", c.target.text)
    }
}
