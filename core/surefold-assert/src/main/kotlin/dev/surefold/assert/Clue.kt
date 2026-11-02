package dev.surefold.assert

/** Runs [block], and any check in it that fails says [clue] first. Blocks nest, the outermost clue first. */
inline fun <R> withClue(clue: Any?, block: () -> R): R = withClue({ clue }, block)

/** The same, with the clue built only if a check fails. */
inline fun <R> withClue(noinline clue: () -> Any?, block: () -> R): R {
    pushClue(clue)
    try {
        return block()
    } finally {
        popClue()
    }
}

// The clues of the withClue blocks this thread is inside, outermost first. Each is built only if a check fails.
private val lazyClues = ThreadLocal.withInitial { ArrayDeque<() -> Any?>() }

@PublishedApi
internal fun pushClue(clue: () -> Any?) {
    lazyClues.get().addLast(clue)
}

@PublishedApi
internal fun popClue() {
    if (lazyClues.get().removeLastOrNull() == null) error("a clue was popped that was never pushed")
}

/** The clues in force on this thread, outermost first, as the lines a failure starts with. */
internal fun cluesNow(): List<String> = lazyClues.get().map { it().toString() }
