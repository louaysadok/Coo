package com.ERT.app.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Two-level cache: a small in-memory LRU in front of a JSON file per entry on disk.
 *
 * Every entry carries the timestamp it was written, and reads are validated against a TTL that the
 * caller chooses per data type (feeds 10 min, detail 30 min, indexes 24 h). Caching is only ever a
 * performance feature here — **Retry always forces a refresh**, and errors are never cached, so a
 * stale entry can never hide a fix or a broken screen.
 */
class Cache(context: Context) {

    private val dir = File(context.cacheDir, ERT_CACHE).apply { mkdirs() }
    private val memory = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > 96
    }

    private data class Entry(val body: String, val atMs: Long)

    companion object {
        const val ERT_CACHE = "ert_cache"
        const val TTL_FEED_MS = 10 * 60 * 1000L
        const val TTL_DETAIL_MS = 30 * 60 * 1000L
        const val TTL_INDEX_MS = 24 * 60 * 60 * 1000L
    }

    fun get(key: String, ttlMs: Long): String? {
        synchronized(this) {
            val m = memory[key]
            if (m != null) return if (System.currentTimeMillis() - m.atMs <= ttlMs) m.body else null.also { memory.remove(key) }
        }
        return readDisk(key, ttlMs)
    }

    fun peek(key: String): String? = get(key, Long.MAX_VALUE)

    fun put(key: String, body: String) {
        val entry = Entry(body, System.currentTimeMillis())
        synchronized(this) { memory[key] = entry }
        try {
            File(dir, fileName(key)).writeText(Json.write(mapOf("at" to entry.atMs, "body" to body)))
        } catch (_: Throwable) {
            // A failed disk write only costs us the offline copy — memory cache still works.
        }
    }

    fun remove(key: String) {
        synchronized(this) { memory.remove(key) }
        try { File(dir, fileName(key)).delete() } catch (_: Throwable) {}
    }

    fun clear() {
        synchronized(this) { memory.clear() }
        try { dir.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    fun sizeBytes(): Long = try { dir.listFiles()?.sumOf { it.length() } ?: 0L } catch (_: Throwable) { 0L }

    private fun readDisk(key: String, ttlMs: Long): String? {
        return try {
            val f = File(dir, fileName(key))
            if (!f.exists()) return null
            val text = f.readText()
            val m = Json.obj(Json.parse(text))
            val at = Json.asLong(m["at"], 0L)
            val body = Json.str(m["body"]) ?: return null
            if (System.currentTimeMillis() - at > ttlMs) {
                f.delete()
                return null
            }
            synchronized(this) { memory[key] = Entry(body, at) }
            body
        } catch (_: Throwable) {
            null
        }
    }

    private fun fileName(key: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { String.format("%02x", it) }.take(48) + ".json"
    } catch (_: Throwable) {
        key.hashCode().toString() + ".json"
    }
}
