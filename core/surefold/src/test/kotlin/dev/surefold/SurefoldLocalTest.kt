package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SurefoldLocalTest {

    @Test
    fun `an unbound local answers with its initial value`() {
        val local = surefoldLocal { "initial" }

        local.get() shouldBe "initial"
    }

    @Test
    fun `unbound answers the initial value inside a binding`() {
        val local = surefoldLocal { "initial" }

        local.locally("bound") { local.unbound() } shouldBe "initial"
    }

    @Test
    fun `a binding is put back when its block leaves`() {
        val local = surefoldLocal { "initial" }

        local.locally("bound") { local.get() } shouldBe "bound"
        local.get() shouldBe "initial"
    }

    @Test
    fun `a value bound outside a parMap is readable in every branch`() {
        val local = surefoldLocal { "initial" }

        val seen = local.locally("bound") { parMap(listOf(1, 2, 3)) { local.get() } }

        seen shouldContainExactly listOf("bound", "bound", "bound")
    }

    @Test
    fun `a branch does not see a binding made after it forked`() {
        val local = surefoldLocal { "initial" }
        val forked = CountDownLatch(1)
        val bound = CountDownLatch(1)

        val seen = scope {
            val branch = async {
                forked.countDown()
                bound.await()
                local.get()
            }
            forked.await()
            local.locally("later") { bound.countDown() }
            branch.await()
        }

        seen shouldBe "initial"
    }

    @Test
    fun `a binding made inside a branch does not reach its siblings`() {
        val local = surefoldLocal { "initial" }

        val seen = parZip(
            { local.locally("mine") { local.get() } },
            { local.get() },
        ) { first, second -> first to second }

        seen shouldBe ("mine" to "initial")
    }

    @Test
    fun `a fork on a given executor inherits too`() {
        val pool = Executors.newFixedThreadPool(2)
        val local = surefoldLocal { "initial" }

        try {
            val seen = local.locally("bound") { parMap(on = pool, iterable = listOf(1, 2)) { local.get() } }

            withClue("the capture is at the fork, not inside VirtualThreads") {
                seen shouldContainExactly listOf("bound", "bound")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an attach on a thread nothing has bound on lasts until its detach`() {
        val local = surefoldLocal { "initial" }

        val detach = local.attach("attached")
        val during = local.get()
        detach.detach()

        during shouldBe "attached"
        local.get() shouldBe "initial"
    }

    @Test
    fun `an attach inside a binding lasts for the rest of that block and no further`() {
        val local = surefoldLocal { "initial" }

        val seen = local.locally("bound") {
            local.attach("attached")
            local.get()
        }

        seen shouldBe "attached"
        withClue("leaving the block drops what was attached inside it, detached or not") {
            local.get() shouldBe "initial"
        }
    }

    @Test
    fun `a fork inherits what was attached before it`() {
        val local = surefoldLocal { "initial" }
        val other = surefoldLocal { 0 }

        val seen = other.locally(1) {
            val detach = local.attach("attached")
            try {
                parMap(listOf(1, 2)) { local.get() to other.get() }
            } finally {
                detach.detach()
            }
        }

        seen shouldContainExactly listOf("attached" to 1, "attached" to 1)
    }

    @Test
    fun `an attach in a branch does not reach the thread that forked it`() {
        val local = surefoldLocal { "initial" }

        val seen = local.locally("bound") {
            parMap(listOf(1)) { local.attach("branch's own") }
            local.get()
        }

        seen shouldBe "bound"
    }
}
