package com.ERT.app.core

import android.content.Context

/**
 * Bookmarks, history and settings, all in SharedPreferences as JSON (with [Json], so the format is
 * under our control and unit-testable rather than depending on a serialisation plugin).
 *
 * Malformed entries are dropped on read instead of crashing — a bad bookmark must never prevent
 * the app from opening.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val PREFS = "ert_prefs"
        private const val K_BOOKMARKS = "bookmarks"
        private const val K_HISTORY = "history"
        private const val K_MATURE = "mature"
        private const val K_CLIENT_ID = "client_id"
        private const val K_CLIENT_SECRET = "client_secret"
        private const val K_TOKEN = "token"
        private const val K_TOKEN_EXPIRY = "token_expiry_ms"
        private const val K_HITS = "recent_hits"
        private const val HISTORY_LIMIT = 200
    }

    // ------------------------------------------------------------- bookmarks

    fun bookmarks(): List<MediaItem> = readList(K_BOOKMARKS).mapNotNull { MediaItem.fromMap(Json.obj(it)) }

    fun isBookmarked(id: String): Boolean = bookmarks().any { it.id == id }

    fun toggleBookmark(item: MediaItem): Boolean {
        val list = readList(K_BOOKMARKS).toMutableList()
        val idx = list.indexOfFirst { Json.str(Json.obj(it)["id"]) == item.id }
        val added = if (idx >= 0) {
            list.removeAt(idx)
            false
        } else {
            list.add(0, item.toMap())
            true
        }
        prefs.edit().putString(K_BOOKMARKS, Json.write(list)).apply()
        return added
    }

    // --------------------------------------------------------------- history

    fun history(): List<Pair<MediaItem, Long>> = readList(K_HISTORY).mapNotNull { raw ->
        val m = Json.obj(raw)
        val item = MediaItem.fromMap(Json.obj(m["item"])) ?: return@mapNotNull null
        item to Json.asLong(m["at"], 0L)
    }

    fun recordView(item: MediaItem) {
        val list = readList(K_HISTORY).toMutableList()
        list.removeAll { Json.str(Json.obj(Json.obj(it)["item"])["id"]) == item.id }
        list.add(0, mapOf("item" to item.toMap(), "at" to System.currentTimeMillis()))
        while (list.size > HISTORY_LIMIT) list.removeAt(list.size - 1)
        prefs.edit().putString(K_HISTORY, Json.write(list)).apply()
    }

    fun clearHistory() = prefs.edit().remove(K_HISTORY).apply()

    // -------------------------------------------------------------- settings

    var mature: Boolean
        get() = prefs.getBoolean(K_MATURE, false)
        set(v) = prefs.edit().putBoolean(K_MATURE, v).apply()

    var clientId: String?
        get() = prefs.getString(K_CLIENT_ID, null)?.ifBlank { null }
        set(v) = prefs.edit().putString(K_CLIENT_ID, v).apply()

    var clientSecret: String?
        get() = prefs.getString(K_CLIENT_SECRET, null)?.ifBlank { null }
        set(v) = prefs.edit().putString(K_CLIENT_SECRET, v).apply()

    val hasCredentials: Boolean get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    var token: String?
        get() {
            val t = prefs.getString(K_TOKEN, null) ?: return null
            if (System.currentTimeMillis() >= prefs.getLong(K_TOKEN_EXPIRY, 0L)) return null
            return t
        }
        set(v) = prefs.edit().putString(K_TOKEN, v).apply()

    fun saveToken(info: TokenInfo) {
        prefs.edit()
            .putString(K_TOKEN, info.accessToken)
            .putLong(K_TOKEN_EXPIRY, System.currentTimeMillis() + (info.expiresInSec * 1000L) - 60_000L)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(K_CLIENT_ID).remove(K_CLIENT_SECRET).remove(K_TOKEN).remove(K_TOKEN_EXPIRY)
            .apply()
    }

    // ------------------------------------------------- request budget (5/hour anonymous)

    /** Timestamps of recent API calls in the last hour — shown in Diagnostics. */
    fun recordHit() {
        val now = System.currentTimeMillis()
        val hits = readLongList(K_HITS).toMutableList()
        hits.add(now)
        prefs.edit().putString(K_HITS, Json.write(hits.filter { now - it < 3_600_000L })).apply()
    }

    fun hitsLastHour(): Int = readLongList(K_HITS).count { System.currentTimeMillis() - it < 3_600_000L }

    // ----------------------------------------------------------------- misc

    private fun readList(key: String): MutableList<Any?> {
        val raw = prefs.getString(key, null) ?: return mutableListOf()
        return try {
            Json.arr(Json.parse(raw)).toMutableList()
        } catch (_: Throwable) {
            mutableListOf() // corrupt storage: start clean rather than crash
        }
    }

    private fun readLongList(key: String): List<Long> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            Json.arr(Json.parse(raw)).mapNotNull { Json.asLong(it, 0L).takeIf { v -> v > 0 } }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
