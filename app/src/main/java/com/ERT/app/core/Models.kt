package com.ERT.app.core

/** Which Openverse media type a request/result belongs to. */
enum class MediaType(val path: String, val label: String) {
    IMAGE("images", "Images"),
    AUDIO("audio", "Audio");

    companion object {
        fun fromPath(p: String): MediaType? = entries.firstOrNull { it.path.equals(p, ignoreCase = true) }
    }
}

data class Tag(val name: String, val accuracy: Double?)

data class AltFile(val url: String?, val bitRate: Int?, val sampleRate: Int?, val filetype: String?)

/**
 * One media record. `url` is the file on the provider's own CDN (never re-hosted);
 * `thumbnail` is the API-side thumbnail proxy (may be null for audio).
 */
data class MediaItem(
    val id: String,
    val mediaType: MediaType,
    val title: String,
    val creator: String?,
    val creatorUrl: String?,
    val url: String?,                 // direct CDN file
    val thumbnail: String?,           // /v1/{type}/{id}/thumb/
    val foreignLandingUrl: String?,
    val license: String?,
    val licenseVersion: String?,
    val licenseUrl: String?,
    val provider: String?,
    val source: String?,
    val category: String?,
    val filetype: String?,
    val filesize: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val tags: List<Tag>,
    val attribution: String?,
    val mature: Boolean,
    val indexedOn: String?,
    val detailUrl: String?,
    val relatedUrl: String?,
    val genres: List<String>,
    val waveform: String?,
    val altFiles: List<AltFile>
) {
    companion object

    val displayTitle: String
        get() = title.ifBlank { (creator?.let { "Untitled — $it" } ?: "Untitled") }

    val licenseLabel: String
        get() = buildString {
            val l = license?.uppercase() ?: return@buildString
            append(if (l == "PDM") "Public domain mark" else "CC $l")
            if (!licenseVersion.isNullOrBlank()) append(" $licenseVersion")
        }.ifBlank { "See source" }
}

/** A paginated search/browse response. */
data class PageResult(
    val items: List<MediaItem>,
    val resultCount: Int,
    val pageCount: Int,
    val page: Int,
    val pageSize: Int,
    val warnings: List<String>,
    val nextPage: Int?
) {
    val hasNext: Boolean get() = nextPage != null
}

/** Row of the A–Z provider index (GET /v1/{type}/stats/). */
data class SourceStat(
    val sourceName: String,
    val displayName: String?,
    val imageCount: Long?,
    val audioCount: Long?
)

data class TokenInfo(val accessToken: String, val expiresInSec: Long, val scope: String?)

/** Everything the UI needs to explain a failure, including the exact URL. */
class ApiError(
    val status: Int,
    val url: String,
    val detail: String,
    val kind: Kind = Kind.OTHER,
    val retryAfterSec: Long? = null
) : RuntimeException(detail) {
    enum class Kind { RATE_LIMIT, NOT_FOUND, BAD_REQUEST, AUTH, OFFLINE, TIMEOUT, SERVER, OTHER }

    /** Self-explaining message: "Not found (404) · /images/<id>". */
    val uiMessage: String
        get() = "$detail ($status) · ${Url.pretty(url)}"

    companion object {
        fun of(status: Int, url: String, detail: String, retryAfter: Long? = null): ApiError {
            val kind = when (status) {
                404 -> Kind.NOT_FOUND
                400 -> Kind.BAD_REQUEST
                401, 403 -> Kind.AUTH
                429 -> Kind.RATE_LIMIT
                in 500..599 -> Kind.SERVER
                else -> Kind.OTHER
            }
            return ApiError(status, url, detail, kind, retryAfter)
        }
    }
}

// ---------------------------------------------------------------------------
// Serialisation for local storage (bookmarks / history).
//
// Deliberately a compact allow-list of fields rather than reflection: it keeps the on-disk format
// stable across app versions, and it is pure Kotlin so the round-trip is unit-tested
// (see "bookmark roundtrip" in tools/tests/CoreTest.kt).
// ---------------------------------------------------------------------------

fun MediaItem.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "type" to mediaType.path,
    "title" to title,
    "creator" to creator,
    "url" to url,
    "thumb" to thumbnail,
    "license" to license,
    "licenseVersion" to licenseVersion,
    "licenseUrl" to licenseUrl,
    "provider" to provider,
    "source" to source,
    "landing" to foreignLandingUrl,
    "width" to width,
    "height" to height,
    "duration" to durationMs,
    "filetype" to filetype,
    "mature" to mature,
    "tags" to tags.map { mapOf("name" to it.name, "accuracy" to it.accuracy) }
)

fun MediaItem.Companion.fromMap(m: Map<String, Any?>): MediaItem? {
    val id = Json.str(m["id"]) ?: return null
    val type = MediaType.fromPath(Json.str(m["type"]) ?: "images") ?: MediaType.IMAGE
    return MediaItem(
        id = id,
        mediaType = type,
        title = Json.str(m["title"]) ?: "",
        creator = Json.str(m["creator"]),
        creatorUrl = null,
        url = Url.normalizeMedia(Json.str(m["url"])),
        thumbnail = Url.normalizeMedia(Json.str(m["thumb"])),
        foreignLandingUrl = Url.normalizeMedia(Json.str(m["landing"])),
        license = Json.str(m["license"]),
        licenseVersion = Json.str(m["licenseVersion"]),
        licenseUrl = Url.normalizeMedia(Json.str(m["licenseUrl"])),
        provider = Json.str(m["provider"]),
        source = Json.str(m["source"]),
        category = null,
        filetype = Json.str(m["filetype"]),
        filesize = null,
        width = Json.dbl(m["width"])?.toInt(),
        height = Json.dbl(m["height"])?.toInt(),
        durationMs = Json.dbl(m["duration"])?.toLong(),
        tags = Json.arr(m["tags"]).mapNotNull { t ->
            val to = Json.obj(t)
            Json.str(to["name"])?.let { Tag(it, Json.dbl(to["accuracy"])) }
        },
        attribution = null,
        mature = Json.bool(m["mature"]) ?: false,
        indexedOn = null,
        detailUrl = null,
        relatedUrl = null,
        genres = emptyList(),
        waveform = null,
        altFiles = emptyList()
    )
}
