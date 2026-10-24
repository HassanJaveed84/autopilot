package com.lifeadmin.autopilot.liveeval

import com.lifeadmin.autopilot.ai.research2.CallResult
import com.lifeadmin.autopilot.ai.research2.ModelCall
import com.lifeadmin.autopilot.ai.research2.ResearchCallProvider
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/*
 * Development-only live evaluation support. Lives in the unit-test source set under `liveeval`, which
 * Gradle excludes from every test run unless `-PliveEval=true` is passed (app/build.gradle.kts), so it
 * is never part of the app, the APKs or CI.
 */

/** Reads the key from the environment for the duration of the process. It is never written, logged or reported. */
class EnvApiKeyStore(private val variable: String = "ANTHROPIC_API_KEY") : ApiKeyStore {
    override fun hasKey(): Boolean = get() != null
    override fun get(): String? = System.getenv(variable)?.trim()?.takeIf { it.isNotEmpty() }
    override fun save(key: String) = throw UnsupportedOperationException("The live-eval key store is read-only")
    override fun delete() = throw UnsupportedOperationException("The live-eval key store is read-only")
    override fun hint(): String? = null
}

/** One provider exchange as the engine made it: the request it built and the reply it got. */
data class Exchange(val index: Int, val call: ModelCall, val result: CallResult, val startedAtMs: Long)

/** Wraps the production provider without changing what it sends or returns; adds no retries. */
class RecordingCallProvider(private val real: ResearchCallProvider) : ResearchCallProvider {
    override val id: String get() = real.id
    val exchanges = java.util.Collections.synchronizedList(ArrayList<Exchange>())

    override suspend fun call(call: ModelCall, apiKey: String): CallResult {
        val started = System.currentTimeMillis()
        val result = real.call(call, apiKey)
        exchanges += Exchange(exchanges.size + 1, call, result, started)
        return result
    }
}

object LiveEvalSafety {
    private val KEY_SHAPE = Regex("""sk-ant-[A-Za-z0-9_\-]{4,}""")

    /** Removes anything key-shaped, and the configured key itself, from text bound for a report. */
    fun redact(text: String, key: String? = System.getenv("ANTHROPIC_API_KEY")): String {
        var t = KEY_SHAPE.replace(text, "[REDACTED]")
        if (!key.isNullOrBlank() && key.length >= 8) t = t.replace(key, "[REDACTED]")
        return t
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Free preflight: asks the Models API whether the key can use [model]. Returns only the HTTP status;
     * the key goes in the request header and nowhere else.
     */
    fun modelStatus(model: String, key: String, baseUrl: String = "https://api.anthropic.com"): Int {
        val c = URL("$baseUrl/v1/models/$model").openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "GET"
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.responseCode
        } finally {
            c.disconnect()
        }
    }
}
