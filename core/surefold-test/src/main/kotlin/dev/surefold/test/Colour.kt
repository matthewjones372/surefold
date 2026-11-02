package dev.surefold.test

/** Whether a story's console copy is coloured, decided once per JVM, and the ANSI codes it is coloured with. */
internal object Colour {

    const val BOLD = "1"
    const val DIM = "2"
    const val RED = "31"
    const val GREEN = "32"

    val wanted: Boolean by lazy {
        decide(
            property = System.getProperty("surefold.test.colour"),
            noColour = System.getenv("NO_COLOR"),
            forceColour = System.getenv("FORCE_COLOR"),
            underIntelliJ = System.getProperty("idea.test.cyclic.buffer.size") != null,
        )
    }

    /** `always` and `never` win outright; then `NO_COLOR` turns it off, `FORCE_COLOR` or IntelliJ on. */
    fun decide(property: String?, noColour: String?, forceColour: String?, underIntelliJ: Boolean): Boolean = when {
        property == "always" -> true
        property == "never" -> false
        !noColour.isNullOrEmpty() -> false
        !forceColour.isNullOrEmpty() -> true
        else -> underIntelliJ
    }
}

/** Paints text when [on], and leaves it alone when not. */
internal class Ink(private val on: Boolean) {

    fun bold(text: String) = paint(Colour.BOLD, text)

    fun dim(text: String) = paint(Colour.DIM, text)

    fun red(text: String) = paint(Colour.RED, text)

    fun green(text: String) = paint(Colour.GREEN, text)

    private fun paint(code: String, text: String) = if (on) "\u001B[${code}m$text\u001B[0m" else text
}
