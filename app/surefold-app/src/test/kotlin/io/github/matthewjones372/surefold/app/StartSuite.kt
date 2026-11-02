package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf

private class Root
private class Left
private class Right
private class Leaf

object StartSuite : Suite() {
    init {
        test("the root is handed to the block") {
            val module = single<Leaf> { Leaf() } + single { _: Leaf -> Root() }

            module.use { root: Root -> root }.getOrNull().shouldNotBeNull()
        }

        test("a node is built once, however many need it") {
            val built = AtomicInteger()
            val module = single<Leaf> { built.incrementAndGet(); Leaf() } +
                single { _: Leaf -> Left() } +
                single { _: Leaf -> Right() } +
                single { _: Left, _: Right -> Root() }

            module.use { _: Root -> }.getOrNull().shouldNotBeNull()

            built.get() shouldBe 1
        }

        test("independent nodes start on virtual threads of their own") {
            val threads = ConcurrentHashMap.newKeySet<String>()
            val virtual = ConcurrentHashMap.newKeySet<Boolean>()
            fun record() {
                threads.add(Thread.currentThread().name + Thread.currentThread().threadId())
                virtual.add(Thread.currentThread().isVirtual)
            }

            val module = single<Left> { record(); Left() } +
                single<Right> { record(); Right() } +
                single { _: Left, _: Right -> Root() }

            module.use { _: Root -> }.getOrNull().shouldNotBeNull()

            threads shouldHaveSize 2
            virtual shouldContainExactly setOf(true)
        }

        test("a node that refuses leaves nothing acquired") {
            val released = mutableListOf<String>()
            val module = single<Leaf> { install({ Leaf() }) { _, _ -> released += "leaf" } } +
                single<Root, Leaf> { refuse("the port is already taken") }

            module.use { _: Root -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Refused> { refusal ->
                    refusal.reason shouldBe "the port is already taken"
                }
                withClue("what was acquired before the refusal is given back") {
                    released shouldContainExactly listOf("leaf")
                }
            }
        }

        test("a node that installs and then refuses has what it installed given back") {
            val released = mutableListOf<String>()
            val module = single<Root> {
                install({ Leaf() }) { _, _ -> released += "own" }
                refuse("the provider did not start")
            }

            module.use { _: Root -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused>()

            withClue("the refusing node's own release ran, though the node was never built") {
                released shouldContainExactly listOf("own")
            }
        }

        test("a sibling built in the layer that refused is given back too, before the layers below it") {
            val order = Collections.synchronizedList(mutableListOf<String>())
            val leftInstalled = CountDownLatch(1)
            val module = single<Leaf> { install({ Leaf() }) { _, _ -> order += "leaf" } } +
                single { _: Leaf ->
                    install({ Left() }) { _, _ -> order += "left" }.also { leftInstalled.countDown() }
                } +
                single<Right, Leaf> {
                    // Refuses only once Left, in the same layer, has installed: the release a layer-wide skip would
                    // lose.
                    leftInstalled.await()
                    refuse("no")
                }

            module.use { _: Left -> }.leftOrNull().shouldBeInstanceOf<StartupError.Refused>()

            withClue("Left built beside the refusing Right and needs Leaf, so it is given back first") {
                order shouldContainExactly listOf("left", "leaf")
            }
        }

        test("releases run in reverse topological order") {
            val released = mutableListOf<String>()
            val module = single<Leaf> { install({ Leaf() }) { _, _ -> released += "leaf" } } +
                single { _: Leaf -> install({ Left() }) { _, _ -> released += "left" } } +
                single { _: Left -> install({ Root() }) { _, _ -> released += "root" } }

            module.use { _: Root -> }.getOrNull().shouldNotBeNull()

            released shouldContainExactly listOf("root", "left", "leaf")
        }

        test("a graph that does not validate never builds") {
            val module = single<Root, Leaf> { error("a recipe must not run when the graph is short of a key") }

            module.use { _: Root -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Unwireable> { unwireable ->
                    unwireable.errors.head.shouldBeInstanceOf<WiringError.Missing> { fault ->
                        fault.copy(site = null) shouldBe
                            WiringError.Missing(key = typeOf<Leaf>(), neededBy = typeOf<Root>())
                    }
                }
            }
        }

        test("asking for a type the graph does not build names it") {
            single<Leaf> { Leaf() }.use { _: Root -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.NoSuchNode> { missing -> missing.key shouldBe typeOf<Root>() }
            }
        }
    }
}
