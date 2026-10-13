package com.ERT.app.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The single entry point the UI talks to: cache -> network, with authentication, cancellation and
 * per-type TTLs.
 *
 * Cancellation: every call site passes a slot key ("images", "detail", …). [cancel] cancels that
 * slot's Job and aborts the in-flight socket, which is what makes the Cancel button next to each
 * spinner actually work. A cancelled call throws [CancellationException] and the UI simply drops
 * back to its previous state — it is never shown as an error.
 */
class Repo(context: Context) {

    val store = Store(context)
    private val cache = Cache(context)
    private val jobs = ConcurrentHashMap<String, Job>()

    companion object {
        const val SLOT_IMAGES = "images"
        const val SLOT_AUDIO = "audio"
        const val SLOT_SEARCH = "search"
        const val SLOT_DETAIL = "detail"
        const val SLOT_RELATED = "related"
        const val SLOT_STATS = "stats"
        const val SLOT_RAW = "raw"
    }

    // ------------------------------------------------------------------ feeds

    data class FeedResult(val page: PageResult, val fromCache: Boolean, val url: String)

    suspend fun feed(slot: String, query: Requests.FeedQuery, force: Boolean = false): FeedResult =
        runSlot(slot) {
            val url = query.toUrl()
            if (!force) {
                cache.get(url, Cache.TTL_FEED_MS)?.let {
                    return@runSlot FeedResult(Parser.parseSearch(it, query.type), true, url)
                }
            }
            val bearer = tokenOrNull()
            val res = Http.get(url, bearer)
            store.recordHit()
            val page = Parser.parseSearch(res.body, query.type)
            cache.put(url, res.body)
            FeedResult(page, false, url)
        }

    suspend fun detail(slot: String, type: MediaType, id: String, force: Boolean = false): MediaItem =
        runSlot(slot) {
            val url = Requests.detail(type, id)
            if (!force) {
                cache.get(url, Cache.TTL_DETAIL_MS)?.let { return@runSlot Parser.parseDetail(it, type) }
            }
            val res = Http.get(url, tokenOrNull())
            store.recordHit()
            cache.put(url, res.body)
            Parser.parseDetail(res.body, type)
        }

    suspend fun related(slot: String, type: MediaType, id: String, force: Boolean = false): PageResult =
        runSlot(slot) {
            val url = Requests.related(type, id)
            if (!force) {
                cache.get(url, Cache.TTL_DETAIL_MS)?.let { return@runSlot Parser.parseSearch(it, type) }
            }
            val res = Http.get(url, tokenOrNull())
            store.recordHit()
            cache.put(url, res.body)
            Parser.parseSearch(res.body, type)
        }

    suspend fun stats(slot: String, type: MediaType, force: Boolean = false): List<SourceStat> =
        runSlot(slot) {
            val url = Requests.stats(type)
            if (!force) {
                cache.get(url, Cache.TTL_INDEX_MS)?.let { return@runSlot Parser.parseStats(it) }
            }
            val res = Http.get(url, tokenOrNull())
            store.recordHit()
            cache.put(url, res.body)
            Parser.parseStats(res.body)
        }

    // ------------------------------------------------------------ diagnostics

    /** Result of a Diagnostics fetch — everything needed to debug a future breakage. */
    data class RawResult(
        val url: String,
        val status: Int,
        val body: String,
        val ms: Long,
        val fromCache: Boolean,
        val summary: String
    ) {
        val prettyBody: String get() = Json.pretty(body)
        val isError: Boolean get() = status !in 200..299
    }

    /** Fetch any URL/path with no parsing assumptions; used by the Diagnostics screen and presets. */
    suspend fun raw(url: String, force: Boolean = true, allowCache: Boolean = false): RawResult =
        runSlot(SLOT_RAW) {
            val target = Requests.resolve(url)
            if (allowCache && !force) {
                cache.peek(target)?.let { return@runSlot RawResult(target, 200, it, 0, true, summarize(target, it)) }
            }
            val started = System.currentTimeMillis()
            return@runSlot try {
                val res = Http.get(target, tokenOrNull())
                store.recordHit()
                if (target.contains("/stats/") || target.contains("?") || target.endsWith("/")) cache.put(target, res.body)
                RawResult(target, res.status, res.body, res.ms, false, summarize(target, res.body))
            } catch (e: ApiError) {
                RawResult(target, e.status, e.detail, System.currentTimeMillis() - started, false, "ERROR: ${e.uiMessage}")
            }
        }

    /** Human summary of a payload: which page kind it is and how much is in it. */
    fun summarize(url: String, body: String): String {
        val value = try { Json.parse(body) } catch (_: Throwable) { null } ?: return "Unparseable (${body.length} bytes)"
        val m = Json.obj(value)
        return when {
            url.contains("/stats/") -> {
                val n = Parser.parseStats(body).size
                "kind: stats · $n sources"
            }
            m.containsKey("results") -> {
                val type = if (url.contains("/audio")) MediaType.AUDIO else MediaType.IMAGE
                val page = Parser.parseSearch(body, type)
                "kind: ${type.path} list · ${page.items.size} items on page ${page.page}/${page.pageCount} · result_count=${page.resultCount}" +
                    (if (page.warnings.isNotEmpty()) " · warnings: ${page.warnings.size}" else "")
            }
            m["id"] != null -> {
                val type = if (url.contains("/audio")) MediaType.AUDIO else MediaType.IMAGE
                val item = Parser.parseItem(value, type)
                "kind: ${type.path} detail · id=${m["id"]} · title=\"${item?.title ?: "?"}\" · url=${item?.url ?: "MISSING"}"
            }
            m.containsKey("access_token") -> "kind: token · ${if (Json.str(m["access_token"]).isNullOrBlank()) "empty" else "ok"}"
            else -> "kind: json object · keys: ${m.keys.take(10).joinToString(", ")}"
        }
    }

    // ------------------------------------------------------------ auth (free API key)

    /** Returns a valid bearer token if the user has registered one, else null (anonymous). */
    suspend fun tokenOrNull(): String? {
        store.token?.let { return it }
        val id = store.clientId ?: return null
        val secret = store.clientSecret ?: return null
        return try {
            val info = fetchToken(id, secret)
            store.saveToken(info)
            info.accessToken
        } catch (_: Throwable) {
            null // fall back to anonymous rather than breaking the screen
        }
    }

    suspend fun fetchToken(clientId: String, clientSecret: String): TokenInfo = withContext(Dispatchers.IO) {
        val res = Http.postForm(
            Requests.TOKEN_URL,
            mapOf("grant_type" to "client_credentials", "client_id" to clientId, "client_secret" to clientSecret)
        )
        Parser.parseToken(res.body)
    }

    suspend fun rateLimitSummary(): String = withContext(Dispatchers.IO) {
        val bearer = store.token ?: return@withContext "Sign in with a free API key to see your live quota."
        val res = Http.get(Requests.RATE_LIMIT_URL, bearer)
        Parser.parseRateLimit(res.body)
    }

    // ---------------------------------------------------------------- plumbing

    fun cancel(slot: String) {
        jobs[slot]?.cancel()
        jobs.remove(slot)
        Http.cancelAll()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        Http.cancelAll()
    }

    fun isRunning(slot: String): Boolean = jobs[slot]?.isActive == true

    fun clearCache() = cache.clear()

    fun cacheBytes(): Long = cache.sizeBytes()

    /**
     * Runs [block] bound to [slot], so that a second request for the same slot replaces the first
     * and a global [cancel] can abort it. Because slots are independent, one slow page can never
     * starve another screen's request.
     */
    private suspend fun <T> runSlot(slot: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val job = kotlinx.coroutines.coroutineContext[Job]!!
        val previous = jobs.put(slot, job)
        previous?.cancel()
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } finally {
            if (jobs[slot] === job) jobs.remove(slot)
        }
    }
}
