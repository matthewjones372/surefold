package dev.surefold.replay

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.atomic.AtomicInteger

/** A count two forks each add one to, by reading it, waiting on a fork of their own, and writing what they read + 1. */
private class Counter {
    var count = 0
}

/** The planted race: a lost update whenever the second fork reads before the first writes. */
private fun addTwice(counter: Counter) {
    counter.count = 0
    scope {
        val adds =
            List(2) {
                async {
                    val seen = counter.count
                    async {}.await()
                    counter.count = seen + 1
                }
            }
        adds.forEach { it.await() }
    }
    check(counter.count == 2) { "an update was lost: the count is ${counter.count}" }
}

private val named = Regex("""order (0x[0-9a-f]+) broke it on run (\d+); replay\((0x[0-9a-f]+)\)""")

object ReplaySuite : Suite() {
    init {
        test("explore finds the lost update within a thousand runs, and replay fails on its order every time") {
            val counter = Counter()
            shouldThrow<AssertionError>({ explore(runs = 1_000) { addTwice(counter) } }) { broke ->
                val match = named.find(broke.message.orEmpty())
                withClue(broke.message.orEmpty()) { (match != null) shouldBe true }
                val order = checkNotNull(match).groupValues[1].removePrefix("0x").toLong(radix = 16)
                broke.cause?.message shouldContain "an update was lost"

                val failed =
                    (1..100).count {
                        try {
                            replay(order) { addTwice(counter) }
                            false
                        } catch (_: IllegalStateException) {
                            true
                        }
                    }
                withClue("runs of order ${match.groupValues[1]} that lost the update") { failed shouldBe 100 }
            }
        }

        test("a count kept with an atomic add passes every order explore tries") {
            val count = AtomicInteger()
            var runs = 0
            explore(runs = 1_000) {
                runs++
                count.set(0)
                scope {
                    val adds =
                        List(2) {
                            async {
                                async {}.await()
                                count.incrementAndGet()
                            }
                        }
                    adds.forEach { it.await() }
                }
                check(count.get() == 2) { "the count is ${count.get()}" }
            }
            runs shouldBe 1_000
        }

        test("the same order runs the forks in the same order every time") {
            fun ran(order: Long): List<Int> {
                val seen = mutableListOf<Int>()
                replay(order) {
                    scope {
                        val forks =
                            List(4) { n ->
                                async {
                                    async {}.await()
                                    synchronized(seen) { seen += n }
                                }
                            }
                        forks.forEach { it.await() }
                    }
                }
                return seen
            }

            val first = ran(0x5eed)
            repeat(20) { ran(0x5eed) shouldBe first }
            withClue("another order changes it, among a few") {
                (listOf(1L, 2L, 3L, 4L, 5L).map(::ran).toSet().size > 1) shouldBe true
            }
        }
    }
}
