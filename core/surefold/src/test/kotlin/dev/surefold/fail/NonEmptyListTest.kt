package dev.surefold.fail

import dev.surefold.assert.shouldBe
import org.junit.jupiter.api.Test

class NonEmptyListTest {

    @Test
    fun `nonEmptyListOf puts its head first and its tail after`() {
        val list = nonEmptyListOf(1, 2, 3)
        list.head shouldBe 1
        list.tail shouldBe listOf(2, 3)
        list.size shouldBe 3
    }

    @Test
    fun `an empty iterable has no non-empty list and any other does`() {
        emptyList<Int>().toNonEmptyListOrNull() shouldBe null
        listOf(1, 2).toNonEmptyListOrNull() shouldBe nonEmptyListOf(1, 2)
    }

    @Test
    fun `it equals the plain list with the same elements`() {
        nonEmptyListOf("a", "b") shouldBe listOf("a", "b")
        nonEmptyListOf("a", "b").hashCode() shouldBe listOf("a", "b").hashCode()
        nonEmptyListOf("a").toString() shouldBe "[a]"
    }

    @Test
    fun `map and plus stay non-empty`() {
        val mapped: NonEmptyList<Int> = nonEmptyListOf("a", "bb").map { it.length }
        mapped shouldBe listOf(1, 2)
        val grown: NonEmptyList<Int> = nonEmptyListOf(1) + 2 + listOf(3, 4)
        grown shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun `a list it was built from can change without changing it`() {
        val source = mutableListOf(1, 2)
        val list = source.toNonEmptyListOrNull()
        source.clear()
        list shouldBe listOf(1, 2)
    }
}
