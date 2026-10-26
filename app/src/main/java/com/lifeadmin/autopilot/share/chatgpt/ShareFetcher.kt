package com.lifeadmin.autopilot.share.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** One raw HTTP exchange. Redirects are never followed by the transport; [ShareFetcher] decides. */
data class RawResponse(
    val status: Int,
    val contentType: String?,
    val location: String?,
    val body: ByteArray,
    /** The body exceeded the byte limit and was cut off. */
    val truncated: Boolean,
)

/** Network seam: one GET, no cookies, no redirects, a hard byte cap. Replaced by fakes in tests. */
fun interface ShareTransport {
    @Throws(IOException::class)
    fun get(url: String, maxBytes: Int): RawResponse
}

data class FetchLimits(
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 15_000,
    /** Wall clock for the whole fetch, redirects included. */
    val totalTimeoutMs: Long = 30_000,
    /** A 96-message conversation was ~1 MB of HTML; 6 MiB leaves room without trusting the server. */
    val maxBytes: Int = 6 * 1024 * 1024,
    val maxRedirects: Int = 3,
)

sealed interface FetchResult {
    data class Page(val html: String) : FetchResult

    sealed interface Failure : FetchResult

    /** A redirect pointed somewhere other than this share link on chatgpt.com. Nothing was requested there. */
    data class RedirectBlocked(val toHost: String?) : Failure
    data object TooManyRedirects : Failure
    data class HttpError(val status: Int) : Failure

    /** 403/429/503: typically a bot challenge or rate limit, not a missing conversation. */
    data class Refused(val status: Int) : Failure
    data class NotHtml(val contentType: String?) : Failure
    data object TooLarge : Failure
    data object Timeout : Failure
    data class Network(val kind: String) : Failure
}

/**
 * Fetches a [ShareLink] with the limits above. Only `https://chatgpt.com/share/<same id>` is ever
 * requested; every redirect target is validated before it is followed.
 */
class ShareFetcher(
    private val transport: ShareTransport = UrlConnectionTransport(),
    private val limits: FetchLimits = FetchLimits(),
) {
    suspend fun fetch(link: ShareLink): FetchResult = try {
        withTimeout(limits.totalTimeoutMs) { withContext(Dispatchers.IO) { fetchBlocking(link) } }
    } catch (e: TimeoutCancellationException) {
        FetchResult.Timeout
    }

    private fun fetchBlocking(link: ShareLink): FetchResult {
        var url = link.url
        repeat(limits.maxRedirects + 1) {
            val r = try {
                transport.get(url, limits.maxBytes)
            } catch (e: SocketTimeoutException) {
                return FetchResult.Timeout
            } catch (e: IOException) {
                return FetchResult.Network(e.javaClass.simpleName)
            }
            when (r.status) {
                in 300..399 -> {
                    val next = r.location?.let { loc -> runCatching { URI(url).resolve(loc.trim()).toString() }.getOrNull() }
                    val target = next?.let(ShareLink::parse)
                    if (target == null || target.id != link.id) {
                        return FetchResult.RedirectBlocked(next?.let { runCatching { URI(it).host }.getOrNull() })
                    }
                    url = next
                }
                200 -> {
                    if (r.truncated) return FetchResult.TooLarge
                    val type = r.contentType?.substringBefore(';')?.trim()?.lowercase()
                    if (type != "text/html") return FetchResult.NotHtml(type)
                    return FetchResult.Page(r.body.toString(Charsets.UTF_8))
                }
                403, 429, 503 -> return FetchResult.Refused(r.status)
                else -> return FetchResult.HttpError(r.status)
            }
        }
        return FetchResult.TooManyRedirects
    }
}

/** [ShareTransport] on [HttpURLConnection]: no redirects, no caches, no cookies sent, bounded read. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = FetchLimits().connectTimeoutMs,
    private val readTimeoutMs: Int = FetchLimits().readTimeoutMs,
    /** Per-request wall clock: a server dripping bytes under the read timeout still stops here. */
    private val maxDurationMs: Long = FetchLimits().totalTimeoutMs,
) : ShareTransport {
    override fun get(url: String, maxBytes: Int): RawResponse {
        val startedAt = System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "text/html")
            conn.setRequestProperty("User-Agent", "Autopilot-ShareImport/0.1 (Android)")
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val (body, truncated) = stream?.use { readCapped(it, if (status == 200) maxBytes else 64 * 1024, startedAt) } ?: (ByteArray(0) to false)
            return RawResponse(status, conn.contentType, conn.getHeaderField("Location"), body, truncated)
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream, max: Int, startedAt: Long): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            if ((System.nanoTime() - startedAt) / 1_000_000 > maxDurationMs) throw SocketTimeoutException("total time exceeded")
            val n = input.read(buf)
            if (n < 0) return out.toByteArray() to false
            if (out.size() + n > max) {
                out.write(buf, 0, max - out.size())
                return out.toByteArray() to true
            }
            out.write(buf, 0, n)
        }
    }
}
