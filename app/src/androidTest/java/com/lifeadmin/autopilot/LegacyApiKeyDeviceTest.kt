package com.lifeadmin.autopilot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.data.repository.SharedPrefsLegacyApiKey
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Real Android Keystore: removing the Anthropic key an earlier build stored deletes both the
 * encrypted blob and its Keystore entry. Test-only file and alias names, so a key on a real phone
 * running these tests is never touched.
 */
@RunWith(AndroidJUnit4::class)
class LegacyApiKeyDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alias = "lifeadmin_ai_api_key_test"
    private val file = "ai_secrets_test"
    private val key = SharedPrefsLegacyApiKey(context, file, alias)
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After fun tearDown() {
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        runCatching { keyStore.deleteEntry(alias) }
    }

    @Test fun removeDeletesTheBlobAndTheKeystoreEntry() {
        // What an earlier build left: an AES key in the Keystore and an encrypted blob in preferences.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit(commit = true) { putString(SharedPrefsLegacyApiKey.BLOB, "AAECAwQFBgcICQoLDA0ODw==") }
        assertTrue(key.isStored())
        assertTrue(keyStore.containsAlias(alias))

        key.remove()

        assertFalse(key.isStored())
        assertFalse(keyStore.containsAlias(alias))
    }
}
