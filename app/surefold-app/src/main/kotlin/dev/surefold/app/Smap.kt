package dev.surefold.app

import java.util.concurrent.ConcurrentHashMap

/**
 * Where a line inside an inlined body was actually written.
 *
 * A `single` is inline, so the frame that called it belongs to the caller's class — but the line the
 * JVM reports is a synthetic one, past the end of the caller's file, standing for a line of
 * `Single.kt`. That is correct for a debugger stepping in and useless for a report, which is why
 * every such line was one nobody could open. The compiler writes the translation back into the class
 * file as a `SourceDebugExtension`, in the `KotlinDebug` stratum of JSR-045's SMAP; this reads it.
 */
internal class Line(val file: String, val line: Int)

internal class Smap private constructor(private val files: Map<Int, String>, private val lines: List<Mapping>) {

    /** Where [output] was written, or null if it is a line of the caller's own file already. */
    fun resolve(output: Int): Line? =
        lines.firstOrNull { output >= it.outputStart && output < it.outputStart + it.repeat * it.increment }
            ?.let { mapping ->
                files[mapping.fileId]?.let { file ->
                    Line(file, mapping.inputStart + (output - mapping.outputStart) / mapping.increment)
                }
            }

    private class Mapping(
        val inputStart: Int,
        val fileId: Int,
        val repeat: Int,
        val outputStart: Int,
        val increment: Int,
    )

    companion object {

        // `inputStart#fileId,repeat:outputStart,increment`, the last three optional.
        private val entry = Regex("""(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?""")

        // `+ 1 Wiring.kt`, with the line under it holding the only mention of the package there is.
        private val declared = Regex("""\+ (\d+) (.+)""")

        /**
         * The `KotlinDebug` stratum, which is the one that names the caller's own file.
         *
         * The `Kotlin` stratum beside it maps the same synthetic lines to the inline function's
         * source, which is the opposite of what a report wants.
         */
        fun of(smap: String): Smap? {
            val body = smap.lineSequence().dropWhile { it != "*S KotlinDebug" }.drop(1)
                .takeWhile { it != "*E" && !it.startsWith("*S ") }
                .toList()
                .takeIf { it.isNotEmpty() } ?: return null

            val files = body.dropWhile { it != "*F" }.drop(1).takeWhile { it != "*L" }
            val lines = body.dropWhile { it != "*L" }.drop(1)

            return Smap(named(files), lines.mapNotNull(::mapped))
        }

        /** The path under a declaration has no extension, so the directory comes from it. */
        private fun named(section: List<String>): Map<Int, String> =
            section.withIndex().mapNotNull { (at, line) ->
                val declaration = declared.matchEntire(line) ?: return@mapNotNull null
                val name = declaration.groupValues[2]
                val path = section.getOrNull(at + 1)?.takeUnless { declared.matches(it) }.orEmpty()
                val directory = path.substringBeforeLast('/', "")
                declaration.groupValues[1].toInt() to if (directory.isEmpty()) name else "$directory/$name"
            }.toMap()

        private fun mapped(line: String): Mapping? = entry.matchEntire(line.trim())?.let { match ->
            val (input, file, repeat, output, increment) = match.destructured
            Mapping(
                inputStart = input.toInt(),
                fileId = file.toIntOrNull() ?: 1,
                repeat = repeat.toIntOrNull() ?: 1,
                outputStart = output.toInt(),
                increment = increment.toIntOrNull() ?: 1,
            )
        }
    }
}

private val smaps = ConcurrentHashMap<Class<*>, Optional>()

/** A cache entry, because a class without an SMAP is the common case and worth remembering too. */
private class Optional(val smap: Smap?)

internal fun smapOf(type: Class<*>): Smap? = smaps.computeIfAbsent(type) { Optional(read(it)) }.smap

/**
 * The SMAP out of a class file, found by its own header rather than by parsing the class.
 *
 * It is written twice — as a `SourceDebugExtension` attribute and as the string of the annotation
 * that carries it — and both are plain bytes between `SMAP` and `*E`, so the header is enough to
 * find one. Walking the constant pool to reach the same string would be a class-file parser this
 * module has no other use for.
 */
private fun read(type: Class<*>): Smap? {
    val name = "${type.name.replace('.', '/')}.class"
    val bytes = (type.classLoader ?: ClassLoader.getSystemClassLoader())
        .getResourceAsStream(name)?.use { it.readBytes() } ?: return null

    // Latin-1 decodes any byte sequence, and everything read out of the SMAP is ASCII.
    val text = String(bytes, Charsets.ISO_8859_1)
    val start = text.indexOf("SMAP\n").takeIf { it >= 0 } ?: return null
    val end = text.indexOf("\n*E", start).takeIf { it >= 0 } ?: return null

    return Smap.of(text.substring(start, end))
}
