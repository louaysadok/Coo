package com.ERT.app.core

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * The app's HTTP client. Plain HttpURLConnection — no third-party dependency — wrapped with the
 * guarantees the UI needs:
 *
 *  - hard time budget: 10 s connect / 25 s read, so no request can hang longer than ~35 s per
 *    attempt (see [MAX_ATTEMPTS] for the retry policy; total worst case is well under 2 minutes);
 *  - [cancelAll] aborts the in-flight connection immediately (the Cancel button next to spinners);
 *  - every failure comes back as an [ApiError] that carries the exact URL, so the UI can print it;
 *  - 429 is never retried and carries `Retry-After` through to the caller.
 */
object Http {

    const val CONNECT_MS = 10_000
    const val READ_MS = 25_000
    const val MAX_ATTEMPTS = 2

    /** Thrown when the user cancels; the UI swallows it (no error state). */
    class Cancelled : Exception("cancelled")

    class Response(
        val status: Int,
        val body: String,
        val url: String,
        val headers: Map<String, String>,
        val ms: Long,
        val fromAttempt: Int
    ) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    @Volatile
    private var current: HttpURLConnection? = null

    fun cancelAll() {
        try {
            current?.disconnect()
        } catch (_: Throwable) {
        }
        current = null
    }

    private val UA = "ERT/1.0 (Android; +https://github.com/louaysadok/Coo) Openverse client"

    fun get(url: String, bearer: String? = null): Response = request(url, "GET", null, bearer)

    fun postForm(url: String, form: Map<String, String>, bearer: String? = null): Response {
        val body = form.entries.joinToString("&") { Url.encode(it.key) + "=" + Url.encode(it.value) }
        return request(url, "POST", body, bearer)
    }

    private fun request(url: String, method: String, formBody: String?, bearer: String?): Response {
        var lastError: Throwable? = null
        val started = System.currentTimeMillis()

        for (attempt in 1..MAX_ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_MS
                    readTimeout = READ_MS
                    instanceFollowRedirects = true
                    requestMethod = method
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                    if (formBody != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    }
                }
                synchronized(this) { current = conn }

                if (formBody != null) {
                    conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
                }

                val status = conn.responseCode
                val headers = LinkedHashMap<String, String>()
                for ((k, values) in conn.headerFields) {
                    if (k != null) headers[k] = values.joinToString(", ")
                }
                val body = readStream(if (status in 200..299) conn.inputStream else conn.errorStream)
                val ms = System.currentTimeMillis() - started

                if (status !in 200..299) {
                    val retryAfter = headers.entries
                        .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                        ?.value?.trim()?.toLongOrNull()
                    val err = Parser.parseError(status, url, body, retryAfter)

                    // Retry transient upstream failures once; never retry a rate limit or a 4xx.
                    if (status in 500..599 && attempt < MAX_ATTEMPTS) {
                        lastError = err
                        sleepBeforeRetry(attempt)
                        continue
                    }
                    throw err
                }
                return Response(status, body, url, headers, ms, attempt)
            } catch (e: Cancelled) {
                throw e
            } catch (e: ApiError) {
                throw e
            } catch (e: SocketTimeoutException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS) { sleepBeforeRetry(attempt); continue }
                throw ApiError(-1, url, "Timed out after ${READ_MS / 1000}s", ApiError.Kind.TIMEOUT)
            } catch (e: InterruptedIOException) {
                throw Cancelled()
            } catch (e: UnknownHostException) {
                throw ApiError(-2, url, "Offline — cannot reach api.openverse.org", ApiError.Kind.OFFLINE)
            } catch (e: Exception) {
                val msg = e.message?.lowercase(Locale.US) ?: ""
                if (msg.contains("cancel") || msg.contains("interrupted")) throw Cancelled()
                lastError = e
                if (attempt < MAX_ATTEMPTS) { sleepBeforeRetry(attempt); continue }
                throw ApiError(-3, url, e.message ?: "Network error", ApiError.Kind.OFFLINE)
            } finally {
                synchronized(this) { if (current === conn) current = null }
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
        throw ApiError(-3, url, lastError?.message ?: "Request failed", ApiError.Kind.OTHER)
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            Thread.sleep(500L * attempt)
        } catch (_: InterruptedException) {
            throw Cancelled()
        }
    }
}
