package com.ERT.app.core

/**
 * Every URL the app can build, in one place. Pure Kotlin + [Url], so the test suite asserts the
 * exact URLs without touching the network.
 */
object Requests {

    /** Sort options. `relevance` only makes sense with a query and takes no direction. */
    enum class Sort(val param: String, val label: String) {
        INDEXED_ON("indexed_on", "Newest"),
        RELEVANCE("relevance", "Relevance")
    }

    enum class Dir(val param: String, val label: String) {
        DESC("desc", "Desc"),
        ASC("asc", "Asc")
    }

    /** Chips offered on the image feed (values verified against the API's own constants). */
    val IMAGE_CATEGORIES = listOf(null to "Any", "photograph" to "Photo", "illustration" to "Illustration", "digitized_artwork" to "Artwork")
    val AUDIO_CATEGORIES = listOf(
        null to "Any", "music" to "Music", "podcast" to "Podcast", "audiobook" to "Audiobook",
        "news" to "News", "pronunciation" to "Pronunciation", "sound_effect" to "Sound effect"
    )
    val ASPECT_RATIOS = listOf(null to "Any", "wide" to "Wide", "tall" to "Tall", "square" to "Square")
    val SIZES = listOf(null to "Any", "small" to "Small", "medium" to "Medium", "large" to "Large")
    val LENGTHS = listOf(null to "Any", "shortest" to "Shortest", "short" to "Short", "medium" to "Medium", "long" to "Long")
    val LICENSE_TYPES = listOf(null to "Any licence", "all" to "All", "all-cc" to "Creative Commons", "commercial" to "Commercial OK", "modification" to "Remix OK")

    /** Anonymous callers are hard-capped at 20 per page and 240 results deep. */
    const val ANON_MAX_PAGE_SIZE = 20
    const val ANON_MAX_DEPTH = 240

    /** A browse/search request. All fields optional except [type]. */
    data class FeedQuery(
        val type: MediaType,
        val q: String? = null,
        val page: Int = 1,
        val pageSize: Int = ANON_MAX_PAGE_SIZE,
        val source: String? = null,
        val excludedSource: String? = null,
        val creator: String? = null,
        val tags: List<String>? = null,
        val title: String? = null,
        val license: String? = null,
        val licenseType: String? = null,
        val category: String? = null,
        val aspectRatio: String? = null,
        val size: String? = null,
        val length: String? = null,
        val extension: String? = null,
        val mature: Boolean = false,
        val sort: Sort? = null,
        val dir: Dir? = null
    ) {
        val isSearch: Boolean get() = !q.isNullOrBlank()

        /** Stable cache key: identical queries share a cache entry. */
        fun key(): String = toUrl()

        fun toUrl(): String {
            val p = linkedMapOf<String, String?>()
            if (!q.isNullOrBlank()) p["q"] = q.trim().take(200)
            p["page"] = page.coerceAtLeast(1).toString()
            p["page_size"] = pageSize.coerceIn(1, ANON_MAX_PAGE_SIZE).toString()
            p["filter_dead"] = "true"

            // Sort: browse defaults to newest-first; a query defaults to relevance (which cannot
            // take a direction).
            val effectiveSort = sort ?: if (isSearch) Sort.RELEVANCE else Sort.INDEXED_ON
            p["unstable__sort_by"] = effectiveSort.param
            if (effectiveSort == Sort.INDEXED_ON) p["unstable__sort_dir"] = (dir ?: Dir.DESC).param

            p["source"] = source
            p["excluded_source"] = excludedSource
            p["creator"] = creator
            if (!tags.isNullOrEmpty()) p["tags"] = tags.joinToString(",")
            p["title"] = title
            p["license"] = license
            p["license_type"] = licenseType
            p["category"] = category
            p["aspect_ratio"] = aspectRatio
            p["size"] = size
            p["length"] = length
            p["extension"] = extension
            if (mature) p["mature"] = "true"
            return Url.api(type.path, p)
        }

        fun nextPage(): FeedQuery = copy(page = page + 1)

        fun with(vararg pairs: Pair<String, Any?>): FeedQuery {
            var f = this
            for ((k, v) in pairs) {
                f = when (k) {
                    "q" -> f.copy(q = v as String?, page = 1)
                    "source" -> f.copy(source = v as String?, page = 1)
                    "creator" -> f.copy(creator = v as String?, page = 1)
                    "tags" -> f.copy(tags = v as List<String>?, page = 1)
                    "category" -> f.copy(category = v as String?, page = 1)
                    "aspectRatio" -> f.copy(aspectRatio = v as String?, page = 1)
                    "size" -> f.copy(size = v as String?, page = 1)
                    "length" -> f.copy(length = v as String?, page = 1)
                    "licenseType" -> f.copy(licenseType = v as String?, page = 1)
                    "sort" -> f.copy(sort = v as Sort?, page = 1)
                    "dir" -> f.copy(dir = v as Dir?, page = 1)
                    "mature" -> f.copy(mature = v as Boolean, page = 1)
                    else -> f
                }
            }
            return f
        }
    }

    fun search(type: MediaType, q: String, page: Int = 1, mature: Boolean = false) =
        FeedQuery(type = type, q = q, page = page, mature = mature)

    fun browse(type: MediaType, page: Int = 1, mature: Boolean = false) =
        FeedQuery(type = type, page = page, mature = mature)

    fun detail(type: MediaType, id: String) = Url.api("${type.path}/$id")

    fun related(type: MediaType, id: String, page: Int = 1) =
        Url.api("${type.path}/$id/related", linkedMapOf("page" to page.toString(), "page_size" to "10"))

    fun stats(type: MediaType) = Url.api("${type.path}/stats")

    // Mounted under /v1/ like everything else (api/conf/urls/__init__.py).
    val TOKEN_URL = Url.api("auth_tokens/token")
    val RATE_LIMIT_URL = Url.api("rate_limit")
    val HEALTH_URL = Url.api("healthcheck")

    /** Presets offered in Diagnostics — one per page type, so a future breakage is one round-trip. */
    data class Preset(val label: String, val path: String)

    val PRESETS: List<Preset> = listOf(
        Preset("Images browse (page 1)", Url.pretty(browse(MediaType.IMAGE).toUrl())),
        Preset("Images search q=cat", Url.pretty(search(MediaType.IMAGE, "cat").toUrl())),
        Preset("Images search q=café (unicode)", Url.pretty(search(MediaType.IMAGE, "café").toUrl())),
        Preset("Images filtered photograph/wide", Url.pretty(FeedQuery(MediaType.IMAGE, category = "photograph", aspectRatio = "wide").toUrl())),
        Preset("Audio browse (page 1)", Url.pretty(browse(MediaType.AUDIO).toUrl())),
        Preset("Audio filtered music", Url.pretty(FeedQuery(MediaType.AUDIO, category = "music").toUrl())),
        Preset("Image stats (sources)", Url.pretty(stats(MediaType.IMAGE))),
        Preset("Healthcheck", Url.pretty(HEALTH_URL)),
        Preset("Rate limit (needs key)", Url.pretty(RATE_LIMIT_URL))
    )

    /** Resolve a Diagnostics path or preset into an absolute URL (accepts both forms). */
    fun resolve(input: String): String {
        val s = input.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        val withSlash = if (s.startsWith("/")) s else "/$s"
        return if (withSlash.startsWith(Url.API_PREFIX)) Url.BASE + withSlash else Url.BASE + Url.API_PREFIX + withSlash.removePrefix("/")
    }
}
