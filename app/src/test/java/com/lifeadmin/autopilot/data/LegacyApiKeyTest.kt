package com.lifeadmin.autopilot.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.SharedPrefsLegacyApiKey
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Anthropic key an earlier build stored is detected, never read, and removed only on request. */
@RunWith(AndroidJUnit4::class)
class LegacyApiKeyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val key = SharedPrefsLegacyApiKey(context, FILE, "lifeadmin_ai_api_key_unit_test")

    @After fun tearDown() = prefs.edit(commit = true) { clear() }

    @Test fun nothingStoredMeansNothingToOffer() {
        assertFalse(key.isStored())
        key.remove()
        assertFalse(key.isStored())
    }

    @Test fun anEncryptedBlobFromAnEarlierBuildIsDetectedAndRemovedOnRequest() {
        // The earlier build's format: one Base64 blob under this name (contents are never read).
        prefs.edit(commit = true) { putString(SharedPrefsLegacyApiKey.BLOB, "AAECAwQFBgcICQoLDA0ODw==") }
        assertTrue(key.isStored())
        assertTrue("detecting it removes nothing", key.isStored())
        // No Android Keystore under Robolectric: removing the blob must still succeed.
        key.remove()
        assertFalse(key.isStored())
        assertFalse(prefs.contains(SharedPrefsLegacyApiKey.BLOB))
    }

    private companion object {
        const val FILE = "ai_secrets_unit_test"
    }
}
