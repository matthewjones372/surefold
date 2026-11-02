@file:OptIn(FailureSeam::class)

package dev.surefold.assert

/** Asserts this equals [expected], and returns it. */
infix fun <A> A.shouldBe(expected: A): A = also { notEqual(expected)?.let(::thrown) }

/** Runs [block], asserts it threw a [T], and returns what it threw. */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?): T {
    val caught = try {
        block()
        null
    } catch (any: Throwable) {
        any
    }
    return if (caught is T) caught else thrown(notThrown("a ${T::class.qualifiedName} thrown", caught))
}
