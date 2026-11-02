package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test

/** `SmallMap` held to the `Map` contract against the `LinkedHashMap` that `outer + pairs` builds (spec 0283). */
class SmallMapTest {

    private val outer = linkedMapOf("tenant" to "acme", "request_id" to "r-1")
    private val inner = arrayOf("tenant" to "globex", "step" to "pay")

    private val small = SmallMap.of(SmallMap.of(emptyMap(), outer.toList().toTypedArray()), inner)
    private val reference: Map<String, String> = outer + inner

    @Test
    fun `it is a SmallMap at this size, so the rest of these tests test it`() {
        (small is SmallMap) shouldBe true
    }

    @Test
    fun `it equals the LinkedHashMap of the same pairs, both ways, and hashes as it does`() {
        withClue("SmallMap equals LinkedHashMap") { (small == reference) shouldBe true }
        withClue("LinkedHashMap equals SmallMap") { (reference == small) shouldBe true }
        small.hashCode() shouldBe reference.hashCode()
        small.toString() shouldBe reference.toString()
    }

    @Test
    fun `an inner pair with an outer key keeps the outer key's place and takes the inner value`() {
        small.toList() shouldBe listOf("tenant" to "globex", "request_id" to "r-1", "step" to "pay")
        small.toList() shouldBe reference.toList()
    }

    @Test
    fun `lookups answer as the LinkedHashMap does, for keys it holds and keys it does not`() {
        listOf("tenant", "request_id", "step", "absent").forEach { key ->
            withClue(key) {
                small[key] shouldBe reference[key]
                small.containsKey(key) shouldBe reference.containsKey(key)
            }
        }
        small.size shouldBe reference.size
        small.containsValue("pay") shouldBe true
    }

    @Test
    fun `an entry equals a map entry with the same pair, and hashes as one`() {
        val entry = small.entries.first()
        val same = mapOf("tenant" to "globex").entries.first()
        (entry == same) shouldBe true
        entry.hashCode() shouldBe same.hashCode()
    }

    @Test
    fun `past the limit the map is a LinkedHashMap with the same pairs in the same order`() {
        val many = Array(SmallMap.MOST + 1) { "k$it" to "v$it" }

        val built = SmallMap.of(emptyMap(), many)

        (built is SmallMap) shouldBe false
        built.toList() shouldBe many.toList()
    }
}
