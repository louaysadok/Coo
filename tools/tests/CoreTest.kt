import com.ERT.app.core.*
import java.io.File

/**
 * Unit tests for the pure-Kotlin core (JSON, URL normalisation, request building, parsing).
 *
 * These run the EXACT source files that are compiled into the APK — the Kotlin files under
 * app/src/main/java/com/ERT/app/core/ — so a passing run here is real evidence about shipping
 * code, not about a test-only twin.
 * Runner: tools/run-tests.sh  (kotlinc + JVM, no Android SDK required).
 */
object CoreTest {

    private var passed = 0
    private val failures = ArrayList<String>()

    private fun check(name: String, cond: Boolean, extra: String = "") {
        if (cond) {
            passed++
        } else {
            failures.add("$name${if (extra.isBlank()) "" else " — $extra"}")
        }
    }

    private fun eq(name: String, expected: Any?, actual: Any?) {
        check(name, expected == actual, "expected <$expected> got <$actual>")
    }

    private fun read(fixture: String): String = File(fixturesDir, fixture).readText(Charsets.UTF_8)

    private lateinit var fixturesDir: File

    @JvmStatic
    fun main(args: Array<String>) {
        fixturesDir = File(args.getOrNull(0) ?: "fixtures")
        check("fixtures dir exists", fixturesDir.isDirectory, fixturesDir.absolutePath)

        urlTests()
        requestTests()
        imageSearchTests()
        emptyTests()
        detailTests()
        statsTests()
        audioTests()
        malformedTests()
        errorTests()
        jsonTests()
        bookmarkTests()

        println("---------------------------------------------")
        println("passed: $passed")
        if (failures.isEmpty()) {
            println("ALL CORE TESTS PASSED")
        } else {
            println("FAILURES (${failures.size}):")
            failures.forEach { println("  ✗ $it") }
            System.exit(1)
        }
    }

    // ------------------------------------------------------------------ URLs

    private fun urlTests() {
        eq("triple slash", "https://cdn.example.org/a.jpg", Url.normalize("https:///cdn.example.org/a.jpg"))
        eq("quad slash", "https://cdn.example.org/a.jpg", Url.normalize("https:////cdn.example.org/a.jpg"))
        eq("protocol relative", "https://api.openverse.org/v1/images/x/thumb/", Url.normalize("//api.openverse.org/v1/images/x/thumb/"))
        eq("api relative", "https://api.openverse.org/v1/images/", Url.normalize("/v1/images/"))
        eq("bare host", "https://cdn.example.org/people/1", Url.normalize("cdn.example.org/people/1"))
        eq("http upgraded for media", "https://example.org/page", Url.normalizeMedia("http://example.org/page"))
        eq("javascript rejected", null, Url.normalize("javascript:alert(1)"))
        eq("data rejected", null, Url.normalize("data:image/png;base64,AAA"))
        eq("blank rejected", null, Url.normalize("   "))
        eq("null rejected", null, Url.normalize(null))
        eq("spaces encoded", "https://x.org/a%20b.jpg", Url.normalize("https://x.org/a b.jpg"))
        eq("unicode encoded", "caf%C3%A9", Url.encode("café"))
        eq("unicode: japanese", "%E3%82%AB%E3%83%95%E3%82%A7", Url.encode("カフェ"))
        eq("ampersand encoded", "a%26b", Url.encode("a&b"))
        eq("pretty print", "/images/?page=2", Url.pretty("https://api.openverse.org/v1/images/?page=2"))
        check("isApi", Url.isApi("https://api.openverse.org/v1/images/"))
        check("not isApi", !Url.isApi("https://live.staticflickr.com/a.jpg"))
    }

    // -------------------------------------------------------------- requests

    private fun requestTests() {
        val browse = Requests.browse(MediaType.IMAGE).toUrl()
        eq(
            "browse url",
            "https://api.openverse.org/v1/images/?page=1&page_size=20&filter_dead=true&unstable__sort_by=indexed_on&unstable__sort_dir=desc",
            browse
        )

        val search = Requests.search(MediaType.IMAGE, "cat").toUrl()
        check("search carries q", search.contains("q=cat"), search)
        check("search defaults to relevance", search.contains("unstable__sort_by=relevance"), search)
        check("relevance takes no direction", !search.contains("sort_dir"), search)

        val unicodeSearch = Requests.search(MediaType.IMAGE, "café").toUrl()
        check("unicode query encoded", unicodeSearch.contains("q=caf%C3%A9"), unicodeSearch)

        val clamped = Requests.FeedQuery(MediaType.IMAGE, pageSize = 500).toUrl()
        check("page size clamped to 20", clamped.contains("page_size=20"), clamped)

        val filtered = Requests.FeedQuery(
            MediaType.IMAGE, category = "photograph", aspectRatio = "wide",
            licenseType = "commercial", source = "flickr", tags = listOf("sunrise", "café")
        ).toUrl()
        check("category", filtered.contains("category=photograph"))
        check("aspect ratio", filtered.contains("aspect_ratio=wide"))
        check("license type", filtered.contains("license_type=commercial"))
        check("source", filtered.contains("source=flickr"))
        check("tags comma joined + encoded", filtered.contains("tags=sunrise%2Ccaf%C3%A9"), filtered)

        eq("detail url", "https://api.openverse.org/v1/images/abc/", Requests.detail(MediaType.IMAGE, "abc"))
        eq("audio detail url", "https://api.openverse.org/v1/audio/abc/", Requests.detail(MediaType.AUDIO, "abc"))
        eq("related url", "https://api.openverse.org/v1/images/abc/related/?page=1&page_size=10", Requests.related(MediaType.IMAGE, "abc"))
        eq("stats url", "https://api.openverse.org/v1/images/stats/", Requests.stats(MediaType.IMAGE))
        eq("token url", "https://api.openverse.org/v1/auth_tokens/token/", Requests.TOKEN_URL)

        eq("resolve api path", "https://api.openverse.org/v1/images/", Requests.resolve("/v1/images/"))
        eq("resolve short path", "https://api.openverse.org/v1/images/", Requests.resolve("images/"))
        eq("resolve absolute", "https://api.openverse.org/v1/images/", Requests.resolve("https://api.openverse.org/v1/images/"))

        val paged = Requests.browse(MediaType.IMAGE).nextPage()
        check("nextPage increments", paged.toUrl().contains("page=2"), paged.toUrl())
        val reFiltered = Requests.browse(MediaType.IMAGE, page = 5).with("category" to "photograph")
        check("changing a filter resets to page 1", reFiltered.toUrl().contains("page=1"), reFiltered.toUrl())

        check("mature off by default", !Requests.browse(MediaType.IMAGE).toUrl().contains("mature"))
        check("mature on", Requests.browse(MediaType.IMAGE, mature = true).toUrl().contains("mature=true"))
        check("presets cover every page type", Requests.PRESETS.size >= 8)
    }

    // ----------------------------------------------------------- image search

    private fun imageSearchTests() {
        val page = Parser.parseSearch(read("images_search.json"), MediaType.IMAGE)
        eq("search: item count", 3, page.items.size)
        eq("search: result count", 4821, page.resultCount)
        eq("search: page count", 241, page.pageCount)
        eq("search: page", 1, page.page)
        eq("search: next page", 2, page.nextPage)
        check("search: hasNext", page.hasNext)
        eq("search: warnings parsed", 1, page.warnings.size)
        check("search: warning text", page.warnings.first().contains("unstable__sort_by"), page.warnings.first())

        val first = page.items[0]
        eq("item 1 id", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d", first.id)
        eq("item 1 title", "Sunrise over the harbour", first.title)
        eq("item 1 creator", "Ada Lovelace", first.creator)
        eq("item 1 direct CDN url", "https://live.staticflickr.com/65535/51234567890_abcdef1234_b.jpg", first.url)
        check("item 1 thumbnail absolute", first.thumbnail?.startsWith("https://api.openverse.org/v1/images/") == true, first.thumbnail ?: "null")
        eq("item 1 width/height", 768 to 1024, first.width to first.height)
        eq("item 1 tags", 3, first.tags.size)
        eq("item 1 tag accuracy", 0.94, first.tags[0].accuracy)
        eq("item 1 license label", "CC BY 2.0", first.licenseLabel)
        check("item 1 not mature", !first.mature)

        val second = page.items[1]
        eq("item 2 triple-slash fixed", "https://upload.wikimedia.org/wikipedia/commons/3/3a/Cafe_window.jpg", second.url)
        check("item 2 protocol-relative thumbnail fixed", second.thumbnail?.startsWith("https://api.openverse.org/") == true, second.thumbnail ?: "null")
        eq("item 2 unicode tag kept", "カフェ", second.tags[0].name)
        eq("item 2 tag accuracy", 0.77, second.tags[0].accuracy)
        eq("item 2 missing accuracy key -> null", null, second.tags[1].accuracy)
        eq("item 2 tag name kept", "window", second.tags[1].name)

        val third = page.items[2]
        eq("item 3 http upgraded", "https://cdn.example.org/files/42.jpg", third.url)
        eq("item 3 null thumbnail", null, third.thumbnail)
        check("item 3 mature", third.mature)
        eq("item 3 blank title falls back", "Untitled — Anonymous", third.displayTitle)
    }

    private fun emptyTests() {
        val page = Parser.parseSearch(read("empty_search.json"), MediaType.IMAGE)
        eq("empty: no items", 0, page.items.size)
        eq("empty: no next page", null, page.nextPage)
        check("empty: hasNext false", !page.hasNext)
        eq("empty: result count", 0, page.resultCount)
    }

    private fun detailTests() {
        val item = Parser.parseDetail(read("images_detail.json"), MediaType.IMAGE)
        check("detail: media url found", item.url?.startsWith("https://") == true, item.url ?: "null")
        eq("detail: direct file", "https://live.staticflickr.com/65535/51234567890_abcdef1234_b.jpg", item.url)
        eq("detail: license label", "CC BY 2.0", item.licenseLabel)
        eq("detail: provider", "flickr", item.provider)
        check("detail: related url", item.relatedUrl?.contains("/related/") == true)
        check("detail: attribution", item.attribution?.contains("CC BY 2.0") == true)
    }

    private fun statsTests() {
        val stats = Parser.parseStats(read("images_stats.json"))
        eq("stats: count", 10, stats.size)
        eq("stats: sorted A-Z first", "ADW", stats.first().displayName)
        eq("stats: sorted A-Z last", "Wikimedia Commons", stats.last().displayName)
        eq("stats: image count", 562144321L, stats.first { it.sourceName == "flickr" }.imageCount)
        eq("stats: null audio count", null, stats.first { it.sourceName == "flickr" }.audioCount)
    }

    private fun audioTests() {
        val page = Parser.parseSearch(read("audio_search.json"), MediaType.AUDIO)
        eq("audio: item count", 2, page.items.size)
        val a = page.items[0]
        eq("audio: duration ms", 92000L, a.durationMs)
        eq("audio: genres", listOf("field recording", "ambient"), a.genres)
        check("audio: thumbnail present", a.thumbnail != null)
        check("audio: waveform", a.waveform != null)
        val b = page.items[1]
        eq("audio: null thumbnail", null, b.thumbnail)
        eq("audio: triple-slash fixed", "https://archive.org/download/piano-sonata/track.mp3", b.url)
        eq("audio: pdm label", "Public domain mark", b.licenseLabel)

        val detail = Parser.parseDetail(read("audio_detail.json"), MediaType.AUDIO)
        eq("audio detail: alt files", 1, detail.altFiles.size)
        eq("audio detail: alt file url", "https://cdn.freesound.org/previews/12/12345_6789-hq.ogg", detail.altFiles[0].url)
        eq("audio detail: alt bitrate", 256000, detail.altFiles[0].bitRate)
        eq("audio detail: duration", 92000L, detail.durationMs)
        check("audio detail: playable url", detail.url?.endsWith(".mp3") == true)
    }

    private fun malformedTests() {
        val page = Parser.parseSearch(read("triple_slash.json"), MediaType.IMAGE)
        eq("malformed: parsed", 1, page.items.size)
        val item = page.items[0]
        eq("malformed: url fixed", "https://cdn.example.org/one/two/three.jpg", item.url)
        eq("malformed: thumbnail fixed", "https://api.openverse.org/v1/images/3f3f3f3f-3f3f-43f3-83f3-3f3f3f3f3f3f/thumb/", item.thumbnail)
        eq("malformed: creator url fixed", "https://cdn.example.org/people/1", item.creatorUrl)
        eq("malformed: http upgraded", "https://example.org/page", item.foreignLandingUrl)
        eq("malformed: javascript url dropped", null, item.licenseUrl)
    }

    private fun errorTests() {
        val e404 = Parser.parseError(404, "https://api.openverse.org/v1/images/nope/", read("error_404.json"))
        eq("404 kind", ApiError.Kind.NOT_FOUND, e404.kind)
        eq("404 status", 404, e404.status)
        check("404 message names the url", e404.uiMessage.contains("/images/nope/"), e404.uiMessage)
        check("404 message format", e404.uiMessage == "Not found. (404) · /images/nope/", e404.uiMessage)

        val e429 = Parser.parseError(429, "https://api.openverse.org/v1/images/", read("error_429.json"), 3600L)
        eq("429 kind", ApiError.Kind.RATE_LIMIT, e429.kind)
        eq("429 retry after", 3600L, e429.retryAfterSec)
        check("429 detail surfaced", e429.detail.contains("throttled"), e429.detail)

        val e400 = Parser.parseError(400, "https://api.openverse.org/v1/images/?unstable__sort_by=popular", read("error_400.json"))
        eq("400 kind", ApiError.Kind.BAD_REQUEST, e400.kind)
        check("400 names the bad field", e400.detail.contains("unstable__sort_by"), e400.detail)

        val html = Parser.parseError(502, "https://api.openverse.org/v1/images/", "<html><body>Bad gateway</body></html>")
        check("html body handled", html.detail.contains("Unexpected response"), html.detail)
        eq("5xx kind", ApiError.Kind.SERVER, html.kind)

        val garbage = Parser.parseError(500, "https://api.openverse.org/v1/images/", "not json at all")
        check("garbage body handled", garbage.detail.isNotBlank(), garbage.detail)
    }

    private fun bookmarkTests() {
        val item = Parser.parseDetail(read("images_detail.json"), MediaType.IMAGE)
        val map = item.toMap()
        val back = MediaItem.fromMap(map)!!
        eq("bookmark roundtrip id", item.id, back.id)
        eq("bookmark roundtrip type", MediaType.IMAGE, back.mediaType)
        eq("bookmark roundtrip title", item.title, back.title)
        eq("bookmark roundtrip creator", item.creator, back.creator)
        eq("bookmark roundtrip url", item.url, back.url)
        eq("bookmark roundtrip thumb", item.thumbnail, back.thumbnail)
        eq("bookmark roundtrip license", item.license, back.license)
        eq("bookmark roundtrip tags", item.tags.size, back.tags.size)
        eq("bookmark roundtrip tag name", "sunrise", back.tags[0].name)

        val audio = Parser.parseDetail(read("audio_detail.json"), MediaType.AUDIO)
        val audioBack = MediaItem.fromMap(audio.toMap())!!
        eq("bookmark audio type", MediaType.AUDIO, audioBack.mediaType)
        eq("bookmark audio duration", 92000L, audioBack.durationMs)

        val unicode = Parser.parseSearch(read("images_search.json"), MediaType.IMAGE).items[1]
        val unicodeBack = MediaItem.fromMap(unicode.toMap())!!
        eq("bookmark unicode title", "カフェの窓辺 / Café window", unicodeBack.title)
        eq("bookmark unicode tag", "カフェ", unicodeBack.tags[0].name)

        // A bookmark saved by an older build without some fields must still load.
        val sparse = MediaItem.fromMap(mapOf("id" to "x", "type" to "images"))!!
        eq("bookmark sparse id", "x", sparse.id)
        eq("bookmark sparse title", "", sparse.title)
        eq("bookmark sparse falls back", "Untitled", sparse.displayTitle)
        eq("bookmark missing id rejected", null, MediaItem.fromMap(mapOf("type" to "images")))
    }

    private fun jsonTests() {
        val src = mapOf<String, Any?>(
            "s" to "héllo \"quoted\" \\ back",
            "n" to 42.0,
            "b" to true,
            "nul" to null,
            "list" to listOf<Any?>(1.0, "two", null),
            "nested" to mapOf<String, Any?>("a" to listOf<Any?>(mapOf<String, Any?>("b" to "c")))
        )
        val text = Json.write(src)
        val back = Json.obj(Json.parse(text))
        eq("json roundtrip string", "héllo \"quoted\" \\ back", Json.str(back["s"]))
        eq("json roundtrip number", 42, Json.asInt(back["n"]))
        eq("json roundtrip bool", true, Json.bool(back["b"]))
        eq("json roundtrip null", null, back["nul"])
        eq("json roundtrip nested", "c", Json.str(Json.obj(Json.arr(Json.obj(back["nested"])["a"])[0])["b"]))

        eq("json escape newline", "\"a\\nb\"", Json.write("a\nb"))
        eq("json asInt fallback", 7, Json.asInt(null, 7))
        eq("json asInt from string", 9, Json.asInt("9", 7))

        var threw = false
        try { Json.parse("{not json}") } catch (_: Throwable) { threw = true }
        check("json rejects garbage", threw)

        val pretty = Json.pretty(read("images_search.json"))
        check("pretty re-parses to same value", Json.write(Json.parse(pretty)) == Json.write(Json.parse(read("images_search.json"))))
        check("pretty is indented", pretty.contains("\n  \"result_count\""), pretty.lines().take(3).joinToString("|"))
        check("pretty keeps unicode unescaped", pretty.contains("カフェ"), "unicode survived")
        eq("pretty handles garbage", "not json", Json.pretty("not json"))

        // A truncated payload must throw, not silently return a half-parsed page.
        threw = false
        try { Json.parse(read("images_search.json").substring(0, 200)) } catch (_: Throwable) { threw = true }
        check("json rejects truncated", threw)
    }
}
