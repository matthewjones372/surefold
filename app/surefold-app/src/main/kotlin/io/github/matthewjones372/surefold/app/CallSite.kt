package io.github.matthewjones372.surefold.app

private val walker: StackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

/**
 * The first frame outside surefold's own node factories, as a source path and a line.
 *
 * `single` and `singleOf` are inline, so their frames are the caller's already; `actor` and
 * `migrations` are not, and naming those would put every actor node in `surefold-app-pekko`. A surefold
 * factory is always a top-level function, so its frame is a file facade, which is what the `Kt`
 * distinguishes from a caller written as a class.
 *
 * The package rides along as a directory — `com/petshop/Wiring.kt:92` rather than `Wiring.kt:92` —
 * because a build that wants to turn this into a link has only the source directories to search, and
 * two modules in a project each having a `Wiring.kt` is the normal case rather than the exotic one.
 * Everything that prints a site for a person to read trims it back.
 */
internal fun callSite(): String? = walker.walk { frames ->
    frames.filter { !surefoldFactory(it.className) }
        .map { frame -> siteOf(frame) }
        .findFirst()
        .orElse(null)
}

private fun siteOf(frame: StackWalker.StackFrame): String? {
    val inlined = smapOf(frame.declaringClass)?.resolve(frame.lineNumber)
    if (inlined != null) return "${inlined.file}:${inlined.line}"
    val file = frame.fileName ?: return null
    return "${directoryOf(frame.className)}$file:${frame.lineNumber}"
}

private fun directoryOf(className: String): String =
    className.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { "${it.replace('.', '/')}/" }.orEmpty()

/** A site as the reader of a report wants it: the file it was written in, and the line. */
internal fun shortly(site: String): String = site.substringAfterLast('/')

private fun surefoldFactory(className: String): Boolean =
    className.startsWith("io.github.matthewjones372.surefold.") &&
        className.substringAfterLast('.').endsWith("Kt")
