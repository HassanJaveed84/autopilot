package com.lifeadmin.autopilot.share.chatgpt

import android.content.ComponentName
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ui.share.ShareImportActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** The outbound hand-off is a plain text share: ChatGPT directly when it takes shared text, the chooser otherwise. */
@RunWith(AndroidJUnit4::class)
class ChatGptShareTest {
    // Launched from the task detail screen, so from an activity: no FLAG_ACTIVITY_NEW_TASK, like any share.
    private val context: Activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val text = "I'd like your help with something on my to-do list"

    private fun installChatGpt() {
        val pm = shadowOf(context.packageManager)
        val component = ComponentName(ChatGptShare.PACKAGE, "com.openai.chatgpt.ShareActivity")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_SEND).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataType("text/plain") })
    }

    @Test fun theIntentIsAPlainTextSendThatNothingSubmits() {
        val i = ChatGptShare.directIntent(text)
        assertEquals(Intent.ACTION_SEND, i.action)
        assertEquals("text/plain", i.type)
        assertEquals(text, i.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(ChatGptShare.PACKAGE, i.`package`)
        assertNull(i.data)
        assertEquals(0, i.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun withoutChatGptTheChooserOpensWithoutAutopilotsOwnShareTarget() {
        assertFalse(ChatGptShare.isAvailable(context))
        assertTrue(ChatGptShare.open(context, text))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val inner = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals(text, inner.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(inner.`package`)
        @Suppress("DEPRECATION")
        val excluded = started.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.map { it as ComponentName }.toTypedArray()
        assertArrayEquals(arrayOf(ComponentName(context, ShareImportActivity::class.java)), excluded)
    }

    @Test fun withChatGptInstalledItOpensDirectly() {
        installChatGpt()
        assertTrue(ChatGptShare.isAvailable(context))
        assertTrue(ChatGptShare.open(context, text))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, started.action)
        assertEquals(ChatGptShare.PACKAGE, started.`package`)
        assertEquals(text, started.getStringExtra(Intent.EXTRA_TEXT))
    }
}
