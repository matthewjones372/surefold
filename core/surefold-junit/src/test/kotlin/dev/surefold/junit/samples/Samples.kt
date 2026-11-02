package dev.surefold.junit.samples

import dev.surefold.Limit
import dev.surefold.Reading
import dev.surefold.Resource
import dev.surefold.counter
import dev.surefold.fail.Either
import dev.surefold.increment
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.Suite
import dev.surefold.junit.alone
import dev.surefold.junit.anyOf
import dev.surefold.junit.expect
import dev.surefold.junit.forAll
import dev.surefold.junit.holds
import dev.surefold.junit.ignore
import dev.surefold.junit.not
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeGreaterThanOrEqual
import dev.surefold.junit.shouldBeInRange
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldBeNull
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.shouldContainKey
import dev.surefold.junit.shouldContainOnly
import dev.surefold.junit.shouldEndWith
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.shouldMatch
import dev.surefold.junit.shouldNotBe
import dev.surefold.junit.shouldNotBeBlank
import dev.surefold.junit.shouldNotBeEmpty
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldNotBeSameInstanceAs
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.shouldNotContainKey
import dev.surefold.junit.shouldStartWith
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.tag
import dev.surefold.junit.withClue
import dev.surefold.logInfo
import dev.surefold.logWarn
import dev.surefold.readings
import dev.surefold.resource
import dev.surefold.scope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Suites that fail on purpose. This module's own `test` task excludes this package, so only the
// tests that select them through EngineTestKit or a Launcher run them.

object MixedSuite : Suite() {
    init {
        test("passes") { expect(true) }
        test("fails an assertion") { throw AssertionError("expected 1 but was 2") }
        test("throws something else") { error("the database was not there") }
    }
}

/** Records what each test was handed and when the resource was released, for the engine test to read. */
object OrderedSuite : SharedSuite<String>(limit = Limit.fixed(1)) {
    val seen: MutableList<String> = mutableListOf()

    override val shared: Resource<String> = resource {
        install({ "db" }) { _, _ -> seen += "released" }
    }

    init {
        test("first") { db -> expect(seen.add("first $db")) }
        test("second") { db -> expect(seen.add("second $db")) }
        test("third") { db -> expect(seen.add("third $db")) }
    }
}

/** Opens a connection, then fails to open the database, so the connection is all there is to release. */
object UnacquirableSuite : SharedSuite<String>() {
    val released: MutableList<String> = CopyOnWriteArrayList()

    override val shared: Resource<String> = resource {
        install({ "connection" }) { connection, _ -> released += connection }
        error("no database")
    }

    init {
        test("first never runs") { expect(true) }
        test("second never runs") { expect(true) }
        test("ignored never runs", ignore("skipped before acquiring")) { expect(true) }
    }
}

/** Its tests pass, and closing what they shared throws. */
object UnreleasableSuite : SharedSuite<Unit>() {
    override val shared: Resource<Unit> = resource {
        install({ }) { _, _ -> error("could not close") }
    }

    init {
        test("passes first") { expect(true) }
        test("passes second") { expect(true) }
    }
}

/** Counts acquisitions, and records at release how many of its sixteen concurrent tests had finished. */
object CountedSuite : SharedSuite<Unit>(limit = Limit.fixed(8)) {
    val acquired = AtomicInteger()
    val finished = AtomicInteger()

    @Volatile
    var finishedAtRelease: Int = -1

    override val shared: Resource<Unit> = resource {
        install({ acquired.incrementAndGet() }) { _, _ -> finishedAtRelease = finished.get() }
    }

    init {
        (1..16).forEach { n ->
            test("counted $n") {
                Thread.sleep(n * 5L)
                expect(finished.incrementAndGet() > 0)
            }
        }
    }
}

/** Each test waits for all eight to be running, so the suite only finishes if they run together. */
object GatedSuite : SharedSuite<CountDownLatch>(limit = Limit.fixed(8)) {
    val virtual: MutableMap<String, Boolean> = ConcurrentHashMap()

    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(8) }

    init {
        (1..8).forEach { n ->
            test("gated $n") { gate ->
                virtual["gated $n"] = Thread.currentThread().isVirtual
                gate.countDown()
                expect(gate.await(10, TimeUnit.SECONDS))
            }
        }
    }
}

/** Would run all at once under its default limit, if `concurrent = false` were ignored. */
object SequentialSuite : Suite(concurrent = false) {
    val seen: MutableList<String> = CopyOnWriteArrayList()

    init {
        (1..8).forEach { n -> test("in turn $n") { expect(seen.add("in turn $n")) } }
    }
}

/** Two concurrent tests on either side of an `alone` one, which reads how many of its suite are running. */
object AloneSuite : Suite(limit = Limit.fixed(8)) {
    private val running = AtomicInteger()
    val seen: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var runningWhileAlone: Int = 0

    private fun together(name: String, gate: CountDownLatch) = test(name) {
        running.incrementAndGet()
        gate.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "$name ran without its partner" }
        seen += name
        expect(running.decrementAndGet() >= 0)
    }

    init {
        val before = CountDownLatch(2)
        together("before a", before)
        together("before b", before)
        test("alone", tag("exclusive") + alone) {
            runningWhileAlone = running.incrementAndGet()
            seen += "alone"
            expect(running.decrementAndGet() == 0)
        }
        val after = CountDownLatch(2)
        together("after a", after)
        together("after b", after)
    }
}

/** A failing test between two that wait for each other, so it fails while both are running. */
object FailingSiblingSuite : SharedSuite<CountDownLatch>(limit = Limit.fixed(3)) {
    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(3) }

    init {
        test("waits first") { gate ->
            gate.countDown()
            expect(gate.await(10, TimeUnit.SECONDS))
        }
        test("fails") { gate ->
            gate.countDown()
            error("failed alone")
        }
        test("waits second") { gate ->
            gate.countDown()
            expect(gate.await(10, TimeUnit.SECONDS))
        }
    }
}

/** Two tests that log while both are running and then fail, a passing one that logs, and one whose fork logs. */
object LoggingSuite : SharedSuite<CountDownLatch>(limit = Limit.fixed(4)) {
    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(2) }

    private fun failsTogether(name: String) = test(name) { gate ->
        logInfo("$name started")
        gate.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "$name ran without its partner" }
        logWarn("$name is about to fail")
        throw AssertionError("$name failed")
    }

    init {
        failsTogether("fails first")
        failsTogether("fails second")
        test("passes quietly") {
            logInfo("nobody should see this")
            expect(true)
        }
        test("fails after its fork logs") {
            scope { async { logInfo("logged from a fork") } }
            error("the fork's line is mine")
        }
    }
}

/** Two tests that count the same metric while both are running, and record the total each one read. */
object MeteredSuite : SharedSuite<CountDownLatch>(limit = Limit.fixed(2)) {
    val totals: MutableMap<String, Double> = ConcurrentHashMap()

    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(2) }

    private fun counts(name: String, times: Int) = test(name) { gate ->
        repeat(times) { counter("hits").increment() }
        gate.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "$name ran without its partner" }
        totals[name] = readings("hits").filterIsInstance<Reading.Total>().sumOf { it.total }
        expect(true)
    }

    init {
        counts("counts one", 1)
        counts("counts two", 2)
    }
}

/** Has a suite's shape but is a class, so there is no one instance to run. */
class NotAnObject : Suite() {
    init {
        test("never found") { expect(true) }
    }
}

/** Checks written every way, each test named for how it should end. */
object ExpectingSuite : SharedSuite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    // Power-assert rewrites calls, not references, so this is `expect` as a caller built without the plugin sees it.
    private val unexplained: (Boolean) -> Unit = ::expect

    init {
        test("passes both") { two ->
            expect(two == 2)
            expect(two + two == 4)
        }
        test("fails both") { two ->
            expect(two == 3)
            expect(two + two == 5)
        }
        test("fails one of two") { two ->
            expect(two == 2)
            expect(two == 3)
        }
        test("passes either") { two -> anyOf({ expect(two == 3) }, { expect(two == 2) }) }
        test("fails either") { two -> anyOf({ expect(two == 3) }, { expect(two == 4) }) }
        test("fails a negated pass") { two -> not { expect(two == 2) } }
        test("passes a negated failure") { two -> not { expect(two == 3) } }
        test("throws before it expects") { error("no expectations reached") }
        test("fails unexplained") { two -> unexplained(two == 3) }
    }
}

/** surefold-junit's matchers, each test named for how it should end. */
object MatchingSuite : SharedSuite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("passes shouldBe and shouldNotBe") { two ->
            two shouldBe 2
            two shouldNotBe 3
        }
        test("fails shouldBe") { two -> two shouldBe 3 }
        test("fails shouldNotBe") { two -> two shouldNotBe 2 }
        test("fails both matchers") { two ->
            two shouldBe 3
            two shouldNotBe 2
        }
        test("passes either matcher") { two -> anyOf({ two shouldBe 3 }, { two shouldBe 2 }) }
        test("passes shouldBeRight and shouldBeLeft") { two ->
            Either.Right(two) shouldBeRight 2
            Either.Left(two) shouldBeLeft 2
        }
        test("fails shouldBeRight") { two -> Either.Left(two) shouldBeRight 2 }
    }
}

/** Spec 0190's shouldThrow: what a block threw, checked as a value. */
object ThrowingSuite : SharedSuite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    /** How many times a [shouldThrow]'s second lambda ran; it must not on a failed throw check. */
    val thenRan = java.util.concurrent.atomic.AtomicInteger()

    init {
        test("passes a matching throw") { two -> shouldThrow<IllegalStateException> { check(two == 3) } }
        test("passes a matching throw, then checks it") { two ->
            shouldThrow<IllegalArgumentException>({ require(two == 3) { "not three" } }) { refusal ->
                refusal.message shouldBe "not three"
            }
        }
        test("fails a missing throw") { two -> shouldThrow<IllegalStateException> { two + 1 } }
        test("fails a wrong type") { _ ->
            shouldThrow<IllegalStateException>({ throw UnsupportedOperationException("other") }) {
                thenRan.incrementAndGet()
                expect(true)
            }
        }
        test("fails the second lambda's check") { _ ->
            shouldThrow<IllegalStateException>({ error("three") }) { thrown -> thrown.message shouldBe "two" }
        }
        test("rethrows an interrupt") { _ -> shouldThrow<IllegalStateException> { throw InterruptedException("stop") } }
    }
}

/** Spec 0190's contains and same-instance matchers. */
object ContainingSuite : SharedSuite<List<String>>() {
    override val shared: Resource<List<String>> = resource { listOf("one", "two") }

    init {
        test("passes every matcher") { words ->
            words shouldContain "two"
            words shouldNotContain "three"
            words.first() shouldContain "ne"
            words.first() shouldNotContain "w"
            words shouldBeSameInstanceAs words
            words shouldNotBeSameInstanceAs words.toList()
        }
        test("fails a string shouldContain") { words -> words.first() shouldContain "w" }
        test("fails a null string") { _ -> null shouldContain "w" }
        test("fails a string shouldNotContain") { words -> words.first() shouldNotContain "n" }
        test("fails a collection shouldContain") { words -> words shouldContain "three" }
        test("fails a collection shouldNotContain") { words -> words shouldNotContain "one" }
        test("fails shouldBeSameInstanceAs") { words -> words shouldBeSameInstanceAs words.toList() }
        test("fails shouldNotBeSameInstanceAs") { words -> words shouldNotBeSameInstanceAs words }
    }
}

/** Spec 0191's clue and narrowing, as values. */
object CluedSuite : SharedSuite<Any>() {
    override val shared: Resource<Any> = resource { "two" }

    /** How many times a [shouldBeInstanceOf]'s lambda ran; it must not on a miss. */
    val thenRan = AtomicInteger()

    /** How many times a lazy clue was built; it must not be when its block holds. */
    val lazyClueBuilt = AtomicInteger()

    init {
        test("passes a narrowed check") { two -> two.shouldBeInstanceOf<String> { it shouldBe "two" } }
        test("fails under a clue") { two ->
            withClue("the outer clue") { withClue("the inner clue") { two shouldBe 3 } }
        }
        test("fails one of two under a clue") { two ->
            withClue("only once") {
                two shouldBe "two"
                two shouldBe 3
            }
        }
        test("fails a narrowing") { two ->
            two.shouldBeInstanceOf<Int> {
                thenRan.incrementAndGet()
                expect(true)
            }
        }
        test("fails a narrowed check") { two -> two.shouldBeInstanceOf<String> { it shouldBe "three" } }
        test("passes under a lazy clue") { two ->
            withClue({
                lazyClueBuilt.incrementAndGet()
                "never built"
            }) { two shouldBe "two" }
        }
        test("fails under a lazy clue") { two -> withClue({ "built from $two" }) { two shouldBe 3 } }
    }
}

/** Spec 0192's matchers: collections in any order, emptiness, null and comparisons, as values. */
object GappedSuite : SharedSuite<List<Int>>() {
    override val shared: Resource<List<Int>> = resource { listOf(1, 2, 2, 3) }

    /** How many times a narrowing matcher's lambda ran on a miss; it must not. */
    val thenRan = AtomicInteger()

    private val right: Either<String, Int> = Either.Right(1)
    private val left: Either<String, Int> = Either.Left("no")

    init {
        test("passes every matcher") { xs ->
            xs shouldContainExactlyInAnyOrder listOf(2, 3, 1, 2)
            xs.shouldContainExactlyInAnyOrder(3, 2, 2, 1)
            emptyList<Int>().shouldBeEmpty()
            xs.shouldNotBeEmpty()
            emptyMap<String, Int>().shouldBeEmpty()
            mapOf("a" to 1).shouldNotBeEmpty()
            xs.firstOrNull().shouldNotBeNull { first -> first shouldBe 1 }
            xs.shouldNotBeNull()
            1 shouldBeLessThan 2
            2 shouldBeLessThanOrEqual 2
            3 shouldBeGreaterThan 2
            2 shouldBeGreaterThanOrEqual 2
            xs shouldContainAll listOf(3, 1)
            mapOf("a" to 1) shouldContainKey "a"
            mapOf("a" to 1) shouldNotContainKey "b"
            mapOf("a" to 1) shouldContain ("a" to 1)
            xs shouldContainExactly listOf(1, 2, 2, 3)
            xs.shouldContainExactly(1, 2, 2, 3)
            xs shouldHaveSize 4
            null.shouldBeNull()
            2 shouldBeInRange 1..3
            mapOf("a" to 1, "b" to 2) shouldContainAll mapOf("b" to 2)
            right.shouldBeRight { n -> n shouldBe 1 }
            left.shouldBeLeft { e -> e shouldBe "no" }
            "surefold-junit" shouldStartWith "surefold"
            "surefold-junit" shouldEndWith "junit"
            "surefold".shouldNotBeBlank()
            "surefold-0197" shouldMatch "surefold-\\d+"
            "surefold-0197" shouldMatch Regex("[a-z]+-\\d+")
            "built at Suite.kt:3" shouldContain Regex(":\\d+$")
            xs shouldContainOnly listOf(3, 2, 1)
            xs.shouldContainAll(3, 1)
            mapOf("a" to 1) shouldContainExactly mapOf("a" to 1)
        }
        test("fails in any order") { xs -> xs shouldContainExactlyInAnyOrder listOf(1, 2, 3) }
        test("fails shouldBeEmpty") { xs -> xs.shouldBeEmpty() }
        test("fails shouldNotBeEmpty on a map") { _ -> emptyMap<String, Int>().shouldNotBeEmpty() }
        test("fails shouldNotBeNull") { xs ->
            xs.firstOrNull { it > 3 }.shouldNotBeNull {
                thenRan.incrementAndGet()
                expect(true)
            }
        }
        test("fails shouldBeLessThan") { _ -> 3 shouldBeLessThan 2 }
        test("fails shouldBeGreaterThanOrEqual") { _ -> 1 shouldBeGreaterThanOrEqual 2 }
        test("fails shouldContainAll") { xs -> xs shouldContainAll listOf(3, 4) }
        test("fails shouldContainKey") { _ -> mapOf("a" to 1) shouldContainKey "b" }
        test("fails shouldNotContainKey") { _ -> mapOf("a" to 1) shouldNotContainKey "a" }
        test("fails shouldContain on a map") { _ -> mapOf("a" to 1) shouldContain ("a" to 2) }
        test("fails shouldContainExactly") { xs -> xs shouldContainExactly listOf(1, 2, 3) }
        test("fails shouldHaveSize") { xs -> xs shouldHaveSize 3 }
        test("fails shouldBeNull") { xs -> xs.firstOrNull().shouldBeNull() }
        test("fails shouldBeInRange") { _ -> 4 shouldBeInRange 1..3 }
        test("fails shouldContainAll on a map") { _ -> mapOf("a" to 1) shouldContainAll mapOf("a" to 1, "b" to 2) }
        test("fails shouldBeRight") { _ ->
            left.shouldBeRight {
                thenRan.incrementAndGet()
                expect(true)
            }
        }
        test("fails shouldContainOnly") { xs -> xs shouldContainOnly listOf(1, 2) }
        test("fails shouldContainExactly on a map") { _ -> mapOf("a" to 1) shouldContainExactly mapOf("a" to 2) }
        test("fails shouldStartWith") { _ -> "surefold-junit" shouldStartWith "junit" }
        test("fails shouldEndWith") { _ -> "surefold-junit" shouldEndWith "surefold" }
        test("fails shouldNotBeBlank") { _ -> " ".shouldNotBeBlank() }
        test("fails shouldMatch") { _ -> "surefold-junit" shouldMatch "surefold" }
        test("fails shouldContain a match") { _ -> "surefold-junit" shouldContain Regex("\\d") }
        test("fails shouldBeLeft") { _ ->
            right.shouldBeLeft {
                thenRan.incrementAndGet()
                expect(true)
            }
        }
    }
}

/** Spec 0197's forAll: every element's checks are kept, and no elements is no checks. */
object ForAllSuite : SharedSuite<List<Int>>() {
    override val shared: Resource<List<Int>> = resource { listOf(1, 2, 3, 4) }

    init {
        test("holds when every element does") { xs -> xs.forAll { x -> x shouldBeLessThan 5 } }
        test("holds on no elements") { _ -> holds { emptyList<Int>().forAll { x -> x shouldBe 0 } } }
        test("fails once for each element that does not") { xs -> xs.forAll { x -> x shouldBeLessThan 3 } }
    }
}
