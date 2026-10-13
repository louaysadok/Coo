package com.ERT.app.core

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * Why not org.json / kotlinx.serialization: this module must stay pure Kotlin so that the exact
 * same parsing code that ships in the APK can be compiled and unit-tested on the JVM with a bare
 * kotlinc (see tools/run-tests.sh). No Android types, no codegen plugin, no reflection.
 *
 * Numbers are kept as Double; callers that want integers use Json.asInt/asLong.
 */
object Json {

    class JsonException(message: String) : RuntimeException(message)

    // ---------------------------------------------------------------- reading

    fun parse(text: String): Any? {
        val p = Peeker(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        return v
    }

    private class Peeker(private val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw JsonException("Unexpected end of input")

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw JsonException("Expected '$c' at $i but found '${if (i < s.length) s[i] else "<eof>"}'")
            i++
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw JsonException("Unexpected end of input")
            return when (val c = s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); null }
                else -> if (c == '-' || c in '0'..'9') readNumber() else throw JsonException("Unexpected character '$c' at $i")
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) throw JsonException("Invalid literal at $i")
            i += word.length
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                out[key] = readValue()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw JsonException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw JsonException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("Unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("Unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("Bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw JsonException("Bad unicode escape: $hex"))
                            }
                            else -> throw JsonException("Bad escape: \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) {
                // '+' and '-' are only legal in the exponent, but being permissive here keeps
                // slightly-off provider payloads from killing a whole page of results.
                if ((s[i] == '+' || s[i] == '-') && i > start && s[i - 1] != 'e' && s[i - 1] != 'E') break
                i++
            }
            val raw = s.substring(start, i)
            val d = raw.toDoubleOrNull() ?: throw JsonException("Bad number: $raw")
            return d
        }
    }

    // ------------------------------------------------------------- accessors

    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
    fun arr(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()
    fun str(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is Boolean -> v.toString()
        else -> v.toString()
    }
    fun dbl(v: Any?): Double? = when (v) {
        is Double -> v
        // Long/Int turn up when a map is read straight from memory (e.g. MediaItem.toMap())
        // rather than from parsed text, where every number arrives as Double.
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Float -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
    fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v == "true" || v == "1"
        is Double -> v != 0.0
        else -> null
    }
    fun asLong(v: Any?, fallback: Long = 0L): Long = dbl(v)?.toLong() ?: fallback
    fun asInt(v: Any?, fallback: Int = 0): Int = asLong(v, fallback.toLong()).toInt().let {
        if (it == Int.MIN_VALUE || it == Int.MAX_VALUE) fallback else it
    }

    // -------------------------------------------------------------- writing

    /** Minimal writer used for the local bookmarks / history / settings JSON. */
    fun write(value: Any?): String = buildString { writeValue(value, this) }

    private fun writeValue(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long -> sb.append(v.toString())
            is Double, is Float -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeValue(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(item, sb)
                }
                sb.append(']')
            }
            else -> writeString(v.toString(), sb)
        }
    }

    /** Re-indent a JSON document for the Diagnostics screen. Falls back to the raw text. */
    fun pretty(text: String): String {
        val value = try { parse(text) } catch (_: Throwable) { return text }
        val sb = StringBuilder()
        writePretty(value, sb, 0)
        return sb.toString()
    }

    private fun writePretty(v: Any?, sb: StringBuilder, indent: Int) {
        val pad = "  ".repeat(indent)
        val padIn = "  ".repeat(indent + 1)
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> sb.append(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padIn)
                    writeString(k.toString(), sb)
                    sb.append(": ")
                    writePretty(value, sb, indent + 1)
                }
                sb.append("\n").append(pad).append("}")
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                var first = true
                for (item in list) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padIn)
                    writePretty(item, sb, indent + 1)
                }
                sb.append("\n").append(pad).append("]")
            }
            else -> writeString(v.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
