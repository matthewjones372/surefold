package dev.surefold.actor.journal.jdbc

/**
 * What an append carried (spec 0123), as the `jsonb` object of strings the `metadata` column holds: written by hand,
 * since it is never more than a flat map of a trace's ids and a few annotations, and read back from Postgres's own
 * rendering of it. Nothing carried is a null, not `{}`, so an append that carries nothing costs no more than before.
 */
internal object Metadata {

    fun write(carried: Map<String, String>): String? =
        if (carried.isEmpty()) {
            null
        } else {
            carried.entries.joinToString(",", "{", "}") { (key, value) -> quoted(key) + ":" + quoted(value) }
        }

    fun read(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val reader = Reader(json)
        return reader.objectOfStrings()
    }

    private fun quoted(text: String): String =
        buildString(text.length + 2) {
            append('"')
            text.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u").append(c.code.toString(HEX).padStart(HEX_DIGITS, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }

    /** Just enough of JSON for `{"key": "value", ...}`: what Postgres renders a flat `jsonb` object of strings as. */
    private class Reader(private val text: String) {
        private var at = 0

        fun objectOfStrings(): Map<String, String> {
            expect('{')
            val read = LinkedHashMap<String, String>()
            if (peek() == '}') return read.also { at++ }
            while (true) {
                val key = string()
                expect(':')
                read[key] = string()
                when (next()) {
                    ',' -> continue
                    '}' -> return read
                    else -> fail("a ',' or '}'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = text.getOrNull(at++) ?: fail("the rest of a string")) {
                    '"' -> return out.toString()
                    '\\' -> out.append(escaped())
                    else -> out.append(c)
                }
            }
        }

        private fun escaped(): Char =
            when (val c = text.getOrNull(at++) ?: fail("an escape")) {
                '"',
                '\\',
                '/' -> c
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> text.substring(at, at + HEX_DIGITS).toInt(HEX).toChar().also { at += HEX_DIGITS }
                else -> fail("an escape, not \\$c")
            }

        private fun expect(c: Char) {
            if (next() != c) fail("'$c'")
        }

        private fun next(): Char {
            skipSpace()
            return text.getOrNull(at++) ?: fail("more")
        }

        private fun peek(): Char? {
            skipSpace()
            return text.getOrNull(at)
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun fail(wanted: String): Nothing =
            throw IllegalArgumentException(
                "journal metadata is not an object of strings: wanted $wanted at $at in $text",
            )
    }

    private const val HEX = 16
    private const val HEX_DIGITS = 4
}
