package dev.surefold.structured

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data object Boom

private data object Slow

object CombinatorsSuite : Suite() {
    init {
        test("parZip combines both branches") {
            attempt<Nothing, Int> { parZip({ 20 }, { 22 }) { a, b -> a + b } } shouldBe 42.right()
        }

        test("parZip runs its branches at the same time") {
            // Each branch waits for the other to arrive: run one after the other, the first would time out.
            val both = CyclicBarrier(2)
            attempt<Nothing, String> {
                parZip({
                    both.await(5, TimeUnit.SECONDS)
                    "a"
                }, {
                    both.await(5, TimeUnit.SECONDS)
                    "b"
                }) { a, b -> a + b }
            } shouldBe "ab".right()
        }

        test("parZip of three keeps each value in its place") {
            attempt<Nothing, String> { parZip({ 1 }, { "two" }, { true }) { a, b, c -> "$a $b $c" } } shouldBe
                "1 two true".right()
        }

        test("the first branch to fail cancels the other and is the answer") {
            val blocker = Blocker()
            val answer = attempt<Boom, String> {
                parZip({ blocker.body() }, {
                    blocker.awaitStart()
                    fail(Boom)
                }) { a, _ -> a }
            }
            answer shouldBe Boom.left()
            blocker.wasInterrupted() shouldBe true
        }

        test("a branch's throw reaches the caller as itself, not an ExecutionException") {
            shouldThrow<IllegalArgumentException> {
                attempt<Nothing, Int> { parZip({ throw IllegalArgumentException("bad") }, { 1 }) { _, b -> b } }
            }
        }

        test("parMap answers in the iterable's order") {
            attempt<Nothing, List<Int>> { parMap(listOf(1, 2, 3)) { it * 10 } } shouldBe listOf(10, 20, 30).right()
        }

        test("parMap answers with the first element to fail") {
            attempt<Int, List<Int>> { parMap(listOf(1, 2, 3)) { if (it == 2) fail(it) else it } } shouldBe 2.left()
        }

        test("raceN answers with the first to finish, on its side, and cancels the loser") {
            val blocker = Blocker()
            val answer = attempt<Nothing, Either<String, String>> {
                raceN({ blocker.body() }, {
                    blocker.awaitStart()
                    "fast"
                })
            }
            answer shouldBe "fast".right().right()
            blocker.wasInterrupted() shouldBe true
        }

        test("a branch that fails first loses the race for everyone") {
            val blocker = Blocker()
            attempt<Boom, Either<String, String>> {
                raceN({ blocker.body() }, {
                    blocker.awaitStart()
                    fail(Boom)
                })
            } shouldBe Boom.left()
        }

        test("timeout answers in time with the block's value") {
            attempt<Slow, Int> { timeout(5.seconds, onTimeout = { Slow }) { 42 } } shouldBe 42.right()
        }

        test("past the deadline, timeout fails with the typed error and the block was interrupted") {
            val blocker = Blocker()
            val answer = attempt<Slow, String> { timeout(100.milliseconds, onTimeout = { Slow }) { blocker.body() } }
            answer shouldBe Slow.left()
            blocker.wasInterrupted() shouldBe true
        }

        test("without an error to fail with, timeout throws as surefold's does") {
            shouldThrow<TimeoutException> {
                attempt<Nothing, String> { timeout(100.milliseconds) { Blocker().body() } }
            }
        }

        test("timeoutOrNull answers null past the deadline") {
            attempt<Nothing, String?> { timeoutOrNull(100.milliseconds) { Blocker().body() } } shouldBe null.right()
        }

        test("a branch's fail inside a timeout is still the answer") {
            attempt<Boom, Int> {
                timeout(5.seconds, onTimeout = { Boom }) { parZip({ fail(Boom) }, { 1 }) { _, b -> b } }
            } shouldBe Boom.left()
        }
    }
}
