package com.ERT.app.core

/**
 * URL normalisation and query building.
 *
 * Provider data in the Openverse catalogue is dirty, and this is the single most common source of
 * "broken" clients, so every URL that leaves the parser goes through [normalize]:
 *
 *   https:///cdn.example.com/a.jpg   ->  https://cdn.example.com/a.jpg   (triple slash)
 *   //cdn.example.com/a.jpg          ->  https://cdn.example.com/a.jpg   (protocol-relative)
 *   /v1/images/<id>/thumb/           ->  https://api.openverse.org/v1/images/<id>/thumb/
 *   http://...                       ->  https://...                     (upgrade where we can)
 *   javascript:/data:/file:/ftp:     ->  null                            (never rendered)
 */
object Url {

    const val BASE = "https://api.openverse.org"
    const val API_PREFIX = "/v1/"

    private val UNRESERVED = Regex("[A-Za-z0-9._~\\-]")

    /** Percent-encode for one query component (UTF-8, spaces as %20). */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (b >= 0 && UNRESERVED.matches(c.toString())) sb.append(c)
            else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Build a query string from already-stringified values; blanks/nulls are skipped. */
    fun query(params: LinkedHashMap<String, String?>): String {
        val parts = ArrayList<String>()
        for ((k, v) in params) {
            if (v.isNullOrBlank()) continue
            parts.add("${encode(k)}=${encode(v)}")
        }
        return parts.joinToString("&")
    }

    /** Full absolute URL for an API path + query, e.g. ("images", {q=cat}) -> https://.../v1/images/?q=cat */
    fun api(path: String, params: LinkedHashMap<String, String?> = linkedMapOf()): String {
        val clean = if (path.startsWith("/")) path else "$API_PREFIX$path"
        val withSlash = if (clean.endsWith("/")) clean else "$clean/"
        val q = query(params)
        return BASE + withSlash + (if (q.isBlank()) "" else "?$q")
    }

    fun normalize(raw: String?): String? {
        var s = raw ?: return null
        s = s.trim()
        if (s.isBlank()) return null

        // Collapse the classic "https:///host/path" and "https:////host/path" bugs.
        s = s.replace(Regex("^(https?):/{3,}"), "$1://")

        // Protocol-relative.
        if (s.startsWith("//")) s = "https:$s"

        // Relative to the API origin.
        if (s.startsWith("/")) s = BASE + s

        // Scheme-less host, e.g. "cdn.example.com/a.jpg".
        if (!s.contains("://")) {
            s = if (s.matches(Regex("^[A-Za-z0-9.\\-]+(/.*)?$"))) "https://$s" else return null
        }

        // Only http(s) survives.
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null

        // Strip whitespace that sneaks into provider data.
        s = s.replace(" ", "%20")

        return s
    }

    /** Same, but also upgrades http -> https (safe for media CDNs; the API is https anyway). */
    fun normalizeMedia(raw: String?): String? {
        val s = normalize(raw) ?: return null
        return if (s.startsWith("http://", true)) "https:" + s.substringAfter(":") else s
    }

    /** "/v1/images/abc/?page=2? -> "/images/abc/?page=2" for error messages and Diagnostics. */
    fun pretty(url: String): String {
        var s = url
        if (s.startsWith(BASE, ignoreCase = true)) s = s.substring(BASE.length)
        if (s.startsWith(API_PREFIX)) s = "/" + s.substring(API_PREFIX.length)
        return s
    }

    /** True when this is an Openverse API url (used to decide whether a tapped link opens in-app). */
    fun isApi(url: String): Boolean = url.startsWith(BASE, ignoreCase = true)
}
