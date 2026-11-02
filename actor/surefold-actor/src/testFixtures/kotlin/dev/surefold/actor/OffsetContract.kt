package dev.surefold.actor

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeNull
import org.junit.jupiter.api.Test

/** What every [OffsetStore] does, as tests a store's own test class inherits: it answers [store], an empty one. */
abstract class OffsetContract {

    /** A store with no offsets in it, fresh for each test. */
    abstract fun store(): OffsetStore

    @Test
    fun `a name with no offset saved has none, and one saved is loaded`() {
        val store = store()

        store.load("totals").shouldBeNull()
        store.save("totals", 7)

        store.load("totals") shouldBe 7L
    }

    @Test
    fun `a save replaces the offset held, and each name has its own`() {
        val store = store()
        store.save("totals", 7)
        store.save("search", 3)

        store.save("totals", 12)

        store.load("totals") shouldBe 12L
        store.load("search") shouldBe 3L
    }
}
