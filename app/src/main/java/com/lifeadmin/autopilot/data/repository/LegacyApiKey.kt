package com.lifeadmin.autopilot.data.repository

import android.content.Context
import androidx.core.content.edit
import java.security.KeyStore

/**
 * The Anthropic API key that a build with built-in AI planning and research may have stored on this
 * phone. Built-in AI is retired, so the app never reads, decrypts or sends the key; it only says
 * whether one is still stored and removes it when the person asks. It is never removed on its own.
 */
interface LegacyApiKey {
    fun isStored(): Boolean
    fun remove()
}

/**
 * The encrypted blob lived in the app-private `ai_secrets` preferences file (excluded from cloud
 * backup, device transfer and the JSON export), sealed with an AES key held in the Android Keystore.
 * Removing clears both.
 */
class SharedPrefsLegacyApiKey(
    context: Context,
    file: String = FILE,
    private val keystoreAlias: String = KEYSTORE_ALIAS,
) : LegacyApiKey {
    private val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    override fun isStored(): Boolean = prefs.contains(BLOB)

    override fun remove() {
        // Synchronous commit: the key is either fully gone or still there.
        prefs.edit(commit = true) { clear() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keystoreAlias) }
    }

    companion object {
        const val FILE = "ai_secrets"
        const val BLOB = "anthropic_key_v1"
        const val KEYSTORE_ALIAS = "lifeadmin_ai_api_key"
    }
}

/** In-memory stand-in for tests. */
class InMemoryLegacyApiKey(private var stored: Boolean = false) : LegacyApiKey {
    override fun isStored() = stored
    override fun remove() { stored = false }
}
