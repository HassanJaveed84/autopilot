package com.lifeadmin.autopilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.security.AndroidKeystoreCipher
import com.lifeadmin.autopilot.ai.security.EncryptedApiKeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android Keystore round trip (Robolectric has no Keystore, so this runs on an emulator/device). */
@RunWith(AndroidJUnit4::class)
class KeystoreApiKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = AndroidKeystoreCipher(alias = "lifeadmin_ai_api_key_test")
    private val store = EncryptedApiKeyStore(context, cipher)
    private val key = "test-key-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"

    @After fun tearDown() = store.delete()

    @Test fun keyIsEncryptedAtRestAndDecryptsWithTheKeystoreKey() {
        store.save(key)
        assertTrue(store.hasKey())
        assertEquals(key, store.get())
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${EncryptedApiKeyStore.FILE}.xml")
        assertTrue(file.exists())
        assertFalse("plaintext key on disk", file.readText().contains(key))
    }

    @Test fun deleteRemovesBlobAndKeystoreEntry() {
        store.save(key)
        store.delete()
        assertFalse(store.hasKey())
        assertNull(store.get())
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(ks.containsAlias("lifeadmin_ai_api_key_test"))
    }
}
