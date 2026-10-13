package com.ERT.app.core

/**
 * Turns Openverse API JSON into the app's models.
 *
 * Contract source: the API's own serializers (see SITE-ANALYSIS.md §3–4). Everything here is
 * defensive: a missing, null, or wrong-typed field degrades to a blank/null and never throws a
 * whole page away. Pure Kotlin + [Json] only, so tools/run-tests.sh compiles and tests this exact
 * file on the JVM.
 */
object Parser {

    // ------------------------------------------------------------------ search

    fun parseSearch(body: String, type: MediaType): PageResult {
        val root = Json.obj(Json.parse(body))
        val results = Json.arr(root["results"])
        val items = ArrayList<MediaItem>(results.size)
        for (r in results) {
            parseItem(r, type)?.let { items.add(it) }
        }

        val page = Json.asInt(root["page"], 1).coerceAtLeast(1)
        val pageCount = Json.asInt(root["page_count"], 1).coerceAtLeast(0)
        val pageSize = Json.asInt(root["page_size"], items.size).coerceAtLeast(1)
        val resultCount = Json.asInt(root["result_count"], items.size).coerceAtLeast(0)

        val warnings = ArrayList<String>()
        for (w in Json.arr(root["warnings"])) {
            val m = Json.obj(w)
            val code = Json.str(m["code"]) ?: "warning"
            val msg = Json.str(m["message"]) ?: Json.write(w)
            warnings.add("$code: $msg")
        }

        // The API clamps counts for anonymous callers; trust hasNext only when we actually got
        // a full page AND the server says there is more, otherwise we'd show a dead "More" button.
        val nextPage = if (items.isNotEmpty() && page < pageCount) page + 1 else null

        return PageResult(
            items = items,
            resultCount = resultCount,
            pageCount = pageCount,
            page = page,
            pageSize = pageSize,
            warnings = warnings,
            nextPage = nextPage
        )
    }

    /** Detail responses are the bare object (no envelope). */
    fun parseDetail(body: String, type: MediaType): MediaItem {
        val root = Json.obj(Json.parse(body))
        return parseItem(root, type) ?: throw ApiError.of(200, "", "Unparseable detail response")
    }

    fun parseItem(raw: Any?, type: MediaType): MediaItem? {
        val m = Json.obj(raw)
        val id = Json.str(m["id"]) ?: return null
        val tags = ArrayList<Tag>()
        for (t in Json.arr(m["tags"])) {
            val to = Json.obj(t)
            val name = Json.str(to["name"])?.trim().orEmpty()
            if (name.isNotBlank()) tags.add(Tag(name, Json.dbl(to["accuracy"])))
        }
        val genres = ArrayList<String>()
        for (g in Json.arr(m["genres"])) {
            Json.str(g)?.trim()?.let { if (it.isNotBlank()) genres.add(it) }
        }
        val altFiles = ArrayList<AltFile>()
        for (a in Json.arr(m["alt_files"])) {
            val ao = Json.obj(a)
            altFiles.add(
                AltFile(
                    url = Url.normalizeMedia(Json.str(ao["url"])),
                    bitRate = Json.dbl(ao["bit_rate"])?.toInt(),
                    sampleRate = Json.dbl(ao["sample_rate"])?.toInt(),
                    filetype = Json.str(ao["filetype"])
                )
            )
        }

        return MediaItem(
            id = id,
            mediaType = type,
            title = Json.str(m["title"])?.trim().orEmpty(),
            creator = Json.str(m["creator"])?.trim()?.ifBlank { null },
            creatorUrl = Url.normalizeMedia(Json.str(m["creator_url"])),
            url = Url.normalizeMedia(Json.str(m["url"])),
            thumbnail = Url.normalizeMedia(Json.str(m["thumbnail"])),
            foreignLandingUrl = Url.normalizeMedia(Json.str(m["foreign_landing_url"])),
            license = Json.str(m["license"]),
            licenseVersion = Json.str(m["license_version"]),
            licenseUrl = Url.normalizeMedia(Json.str(m["license_url"])),
            provider = Json.str(m["provider"]),
            source = Json.str(m["source"]),
            category = Json.str(m["category"]),
            filetype = Json.str(m["filetype"]),
            filesize = Json.dbl(m["filesize"])?.toLong(),
            width = Json.dbl(m["width"])?.toInt(),
            height = Json.dbl(m["height"])?.toInt(),
            durationMs = Json.dbl(m["duration"])?.toLong(),
            tags = tags,
            attribution = Json.str(m["attribution"]),
            mature = Json.bool(m["mature"]) ?: false,
            indexedOn = Json.str(m["indexed_on"]),
            detailUrl = Url.normalizeMedia(Json.str(m["detail_url"])),
            relatedUrl = Url.normalizeMedia(Json.str(m["related_url"])),
            genres = genres,
            waveform = Url.normalizeMedia(Json.str(m["waveform"])),
            altFiles = altFiles
        )
    }

    // ------------------------------------------------------------------- stats

    /** GET /v1/{type}/stats/ -> provider rows for the A–Z screen (not paginated). */
    fun parseStats(body: String): List<SourceStat> {
        val root = Json.parse(body)
        val list = when (root) {
            is Map<*, *> -> Json.arr(root["results"]).ifEmpty { listOf(root) }
            is List<*> -> Json.arr(root)
            else -> emptyList()
        }
        val out = ArrayList<SourceStat>()
        for (r in list) {
            val m = Json.obj(r)
            val name = Json.str(m["source_name"]) ?: Json.str(m["source"]) ?: continue
            out.add(
                SourceStat(
                    sourceName = name,
                    displayName = Json.str(m["display_name"]),
                    imageCount = Json.dbl(m["image_count"])?.toLong(),
                    audioCount = Json.dbl(m["audio_count"])?.toLong()
                )
            )
        }
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: it.sourceName })
    }

    // ------------------------------------------------------------------- auth

    fun parseToken(body: String): TokenInfo {
        val m = Json.obj(Json.parse(body))
        val token = Json.str(m["access_token"])
            ?: throw ApiError.of(200, "", "Token response did not contain access_token")
        return TokenInfo(
            accessToken = token,
            expiresInSec = Json.asLong(m["expires_in"], 3600L).coerceAtLeast(60L),
            scope = Json.str(m["scope"])
        )
    }

    /** GET /rate_limit/ -> a short human summary of the caller's quota. */
    fun parseRateLimit(body: String): String {
        val m = Json.obj(Json.parse(body))
        if (m.isEmpty()) return "No rate-limit information returned."
        val lines = ArrayList<String>()
        for ((k, v) in m) {
            val inner = Json.obj(v)
            val limit = Json.asLong(inner["limit"], -1)
            val remaining = Json.asLong(inner["remaining"], -1)
            if (limit >= 0) lines.add("$k: $remaining / $limit remaining")
            else lines.add("$k: ${Json.write(v)}")
        }
        return lines.joinToString("\n")
    }

    // ------------------------------------------------------------------ errors

    /**
     * DRF error bodies come in several shapes: {"detail": "..."}, {"error": "...", "detail": "..."},
     * per-field objects like {"page_size": ["..."]}, or plain HTML from the edge. Always produce
     * something a human can act on.
     */
    fun parseError(status: Int, url: String, body: String, retryAfterSec: Long? = null): ApiError {
        val parsed = try { Json.parse(body) } catch (_: Throwable) { null }
        val m = Json.obj(parsed)
        val detail = when {
            m.isEmpty() -> {
                val text = body.trim()
                if (text.startsWith("<")) "Unexpected response (${text.length} bytes of HTML) — likely an edge error"
                else text.take(200).ifBlank { httpReason(status) }
            }
            Json.str(m["detail"]) != null -> Json.str(m["detail"])!!
            Json.str(m["error"]) != null -> Json.str(m["error"])!!
            Json.str(m["message"]) != null -> Json.str(m["message"])!!
            else -> {
                // Per-field validation errors: "page_size: Ensure this value is <= 20."
                val first = m.entries.firstOrNull()
                val value = first?.value
                val msg = when (value) {
                    is List<*> -> value.firstOrNull()?.toString()
                    else -> value?.toString()
                }
                if (first != null && msg != null) "${first.key}: $msg" else httpReason(status)
            }
        }
        return ApiError.of(status, url, detail, retryAfterSec)
    }

    private fun httpReason(status: Int): String = when (status) {
        400 -> "Bad request"
        401 -> "Not authenticated"
        403 -> "Not allowed"
        404 -> "Not found"
        429 -> "Rate limited"
        in 500..599 -> "Server error"
        else -> "Request failed"
    }
}
