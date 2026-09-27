package dev.zakadi.sdk.probe

import java.util.Locale

/**
 * The JSON of log format 1: one object per line, keys in the order the map gives them, no
 * whitespace, and ASCII only (every character outside printable ASCII escaped as `\uXXXX`). Values
 * are null, booleans, integers, doubles (at most three decimals, trailing zeros dropped, `null`
 * when not finite), strings, lists and maps with string keys.
 */
object LogJson {
    /** [value] as one line of ASCII JSON. */
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is Int,
            is Long,
            is Short,
            is Byte -> out.append(value.toString())
            is String -> out.append(quote(value))
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    out.append(quote(key as String)).append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("no JSON form for ${value::class}")
        }
    }

    /** At most three decimals, trailing zeros dropped; `null` for NaN and the infinities. */
    fun number(value: Double): String {
        if (!value.isFinite()) return "null"
        var text = String.format(Locale.ROOT, "%.3f", value)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }

    /** [text] quoted, with every character outside printable ASCII escaped. */
    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code in 0x20..0x7E -> out.append(c)
                else -> out.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
            }
        }
        return out.append('"').toString()
    }
}
