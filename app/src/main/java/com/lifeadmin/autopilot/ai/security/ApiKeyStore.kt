package com.lifeadmin.autopilot.ai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Bring-your-own Anthropic API key for this personal, sideloaded build.
 *
 * This is a personal-use compromise, not secret management for a public app: a key stored on a
 * device can in principle be extracted by someone with root access to it. A public multi-user
 * release would need an authenticated server-side proxy that holds the key.
 */
interface ApiKeyStore {
    fun hasKey(): Boolean
    fun get(): String?
    fun save(key: String)
    fun delete()

    /** Last four characters, for "Key saved (…abcd)". Never the full key. */
    fun hint(): String? = get()?.takeLast(4)

    companion object {
        /** Basic shape check; Anthropic keys start with "sk-ant-". */
        fun normalize(raw: String): String? {
            val k = raw.trim().filterNot { it.isWhitespace() }
            return k.takeIf { it.length in 20..300 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        }
    }
}

/** Symmetric encryption seam so the store logic is testable without the Android Keystore. */
interface SecretCipher {
    /** Returns iv + ciphertext. */
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
    fun destroyKey()
}

/** AES-256-GCM key generated inside the Android Keystore; the key material never leaves it. */
class AndroidKeystoreCipher(private val alias: String = "lifeadmin_ai_api_key") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return c.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
    }

    override fun destroyKey() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * Stores only the encrypted blob, in an app-private SharedPreferences file. The file is
 * excluded from cloud backup and device transfer (`allowBackup=false` and
 * data_extraction_rules.xml) and is never part of the JSON export.
 */
class EncryptedApiKeyStore(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreCipher(),
) : ApiKeyStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun hasKey(): Boolean = prefs.contains(BLOB)

    override fun get(): String? {
        val encoded = prefs.getString(BLOB, null) ?: return null
        return runCatching { String(cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8) }
            // Keystore key lost (e.g. device credential reset): the blob is unusable, drop it.
            .onFailure { delete() }
            .getOrNull()
    }

    override fun save(key: String) {
        val clean = ApiKeyStore.normalize(key) ?: throw IllegalArgumentException("That doesn't look like an API key")
        val blob = cipher.encrypt(clean.toByteArray(Charsets.UTF_8))
        // Synchronous commit: the key is either fully stored or not at all.
        prefs.edit(commit = true) { putString(BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)) }
    }

    override fun delete() {
        prefs.edit(commit = true) { clear() }
        cipher.destroyKey()
    }

    companion object {
        const val FILE = "ai_secrets"
        private const val BLOB = "anthropic_key_v1"
    }
}

/** In-memory store for tests. */
class InMemoryApiKeyStore(private var key: String? = null) : ApiKeyStore {
    override fun hasKey() = key != null
    override fun get() = key
    override fun save(key: String) { this.key = ApiKeyStore.normalize(key) ?: throw IllegalArgumentException("That doesn't look like an API key") }
    override fun delete() { key = null }
}
