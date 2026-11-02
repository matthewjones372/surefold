package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldContainExactlyInAnyOrder
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.junit.samples.AloneSuite
import dev.surefold.junit.samples.CountedSuite
import dev.surefold.junit.samples.ExpectingSuite
import dev.surefold.junit.samples.FailingSiblingSuite
import dev.surefold.junit.samples.GatedSuite
import dev.surefold.junit.samples.LoggingSuite
import dev.surefold.junit.samples.MeteredSuite
import dev.surefold.junit.samples.MixedSuite
import dev.surefold.junit.samples.NotAnObject
import dev.surefold.junit.samples.OrderedSuite
import dev.surefold.junit.samples.SequentialSuite
import dev.surefold.junit.samples.UnacquirableSuite
import dev.surefold.junit.samples.UnreleasableSuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.failures
import dev.surefold.junit.testkit.finishOrder
import dev.surefold.junit.testkit.logs
import dev.surefold.junit.testkit.names
import dev.surefold.junit.testkit.shouldHaveFailed
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SurefoldEngineTest {

    private fun run(selector: DiscoverySelector): EngineExecutionResults =
        EngineTestKit.engine("surefold").selectors(selector).execute()

    @Test
    fun `a pass, an assertion and a throw are reported under the tests' names`() {
        val tests = run(selectClass(MixedSuite::class.java)).testEvents()

        tests shouldHaveSucceeded listOf("passes")
        tests.failures().mapValues { (_, thrown) -> thrown::class } shouldContainExactly mapOf(
            "fails an assertion" to AssertionError::class,
            "throws something else" to IllegalStateException::class,
        )
    }

    @Test
    fun `a test's unique ID is its suite's class and its name`() {
        val started = run(selectClass(MixedSuite::class.java)).testEvents().started().list().first()

        started.testDescriptor.uniqueId.toString() shouldBe
            "[engine:surefold]/[suite:${MixedSuite::class.java.name}]/[test:passes]"
    }

    @Test
    fun `a test selected by its unique ID runs alone, as an IDE reruns one`() {
        val id = "[engine:surefold]/[suite:${MixedSuite::class.java.name}]/[test:fails an assertion]"

        run(selectUniqueId(id)).testEvents().started().names() shouldBe listOf("fails an assertion")
    }

    @Test
    fun `a suite selected by its unique ID runs all its tests`() {
        val events = run(selectUniqueId("[engine:surefold]/[suite:${MixedSuite::class.java.name}]")).testEvents()

        events.started().names() shouldContainExactlyInAnyOrder
            listOf("passes", "fails an assertion", "throws something else")
    }

    @Test
    fun `under a limit of one, tests run in the order they were registered, with the resource acquired once`() {
        OrderedSuite.seen.clear()

        run(selectClass(OrderedSuite::class.java)).testEvents().succeeded().count() shouldBe 3L

        OrderedSuite.seen shouldBe listOf("first db", "second db", "third db", "released")
    }

    @Test
    fun `eight tests that wait for each other all finish under a limit of eight, each on a virtual thread`() {
        GatedSuite.virtual.clear()

        val tests = run(selectClass(GatedSuite::class.java)).testEvents()

        tests.succeeded().count() shouldBe 8L
        GatedSuite.virtual shouldContainExactly (1..8).associate { "gated $it" to true }
    }

    @Test
    fun `a suite that is not concurrent runs its tests one at a time, in order`() {
        SequentialSuite.seen.clear()

        run(selectClass(SequentialSuite::class.java)).testEvents().succeeded().count() shouldBe 8L

        SequentialSuite.seen shouldBe (1..8).map { "in turn $it" }
    }

    @Test
    fun `an alone test runs with nothing else in its suite running, after the tests before it`() {
        AloneSuite.seen.clear()

        run(selectClass(AloneSuite::class.java)).testEvents().succeeded().count() shouldBe 5L

        withClue("tests of the suite running while the alone test ran, counting itself") {
            AloneSuite.runningWhileAlone shouldBe 1
        }
        AloneSuite.seen.toList().subList(0, 2).sorted() shouldBe listOf("before a", "before b")
        AloneSuite.seen[2] shouldBe "alone"
    }

    @Test
    fun `a failing test leaves its siblings running and its suite passing`() {
        val events = run(selectClass(FailingSiblingSuite::class.java))

        events.testEvents() shouldHaveSucceeded listOf("waits first", "waits second")
        events.testEvents() shouldHaveFailed listOf("fails")
        events.containerEvents() shouldHaveSucceeded listOf("FailingSiblingSuite", "Surefold")
    }

    @Test
    fun `every started test is finished before its suite is`() {
        val finished = run(selectClass(GatedSuite::class.java)).allEvents().finishOrder()

        finished.take(8) shouldContainExactlyInAnyOrder (1..8).map { "gated $it" }
        finished.drop(8) shouldBe listOf("GatedSuite", "Surefold")
    }

    @Test
    fun `the shared resource is acquired once for many concurrent tests and released after the last finishes`() {
        CountedSuite.acquired.set(0)
        CountedSuite.finished.set(0)

        run(selectClass(CountedSuite::class.java)).testEvents().succeeded().count() shouldBe 16L

        CountedSuite.acquired.get() shouldBe 1
        withClue("tests finished when the resource was released") { CountedSuite.finishedAtRelease shouldBe 16 }
    }

    @Test
    fun `a failed acquisition fails each test with its cause, passes the suite and releases what it installed`() {
        UnacquirableSuite.released.clear()

        val events = run(selectClass(UnacquirableSuite::class.java))

        events.testEvents() shouldHaveFailed listOf("first never runs", "second never runs")
        val causes = events.testEvents().failures().values
        withClue("one throwable, handed to every test") { causes.toSet().size shouldBe 1 }
        causes.first().message shouldBe "no database"
        events.containerEvents() shouldHaveSucceeded listOf("UnacquirableSuite", "Surefold")
        UnacquirableSuite.released shouldBe listOf("connection")
    }

    @Test
    fun `a release that throws fails the suite and leaves its tests' results as they were`() {
        val events = run(selectClass(UnreleasableSuite::class.java))

        events.testEvents() shouldHaveSucceeded listOf("passes first", "passes second")
        events.containerEvents() shouldHaveFailed listOf("UnreleasableSuite")
        events.containerEvents().failureOf("UnreleasableSuite").message shouldBe "could not close"
    }

    @Test
    fun `a package selector finds the suite objects in it and passes over a suite class`() {
        // testEach's groups are containers too, inside their suites; this is about the suites.
        val suites = run(selectPackage(MixedSuite::class.java.packageName)).containerEvents().started()
            .list().map { it.testDescriptor }.filter { it.uniqueId.lastSegment.type != "group" }.map { it.displayName }

        suites.sorted() shouldBe listOf(
            "AloneSuite",
            "BrokenLeaseSuite",
            "CluedSuite",
            "ContainingSuite",
            "CountedSuite",
            "DefaultTimeoutSuite",
            "EachSuite",
            "ExpectingSuite",
            "FailingLeaseSuite",
            "FailingSiblingSuite",
            "FirstTurnSuite",
            "ForAllSuite",
            "GappedSuite",
            "GatedSuite",
            "HoldsSuite",
            "IgnoringSuite",
            "IsolatedTurnSuite",
            "LastTurnSuite",
            "LeasingSuite",
            "LoggingSuite",
            "MatchingSuite",
            "MeetingSuite",
            "MeteredSuite",
            "MixedSuite",
            "OrderSuite",
            "OrderedSuite",
            "OtherMeetingSuite",
            "PartlyTaggedSuite",
            "RecordedSuite",
            "SequentialSuite",
            "SharedEachSuite",
            "StorySuite",
            "Surefold",
            "TaggedSuite",
            "ThrowingSuite",
            "TimingSuite",
            "UnacquirableSuite",
            "UnreleasableSuite",
            "UsingSuite",
            "WaitSuite",
            "WrappedSuite",
        )
        run(selectClass(NotAnObject::class.java)).testEvents().started().count() shouldBe 0L
    }

    @Test
    fun `a failing test's report entry holds its own log lines and none of a sibling's running beside it`() {
        val logs = run(selectClass(LoggingSuite::class.java)).testEvents().logs()

        logs.getValue("fails first") shouldBe listOf(
            "INFO ${LoggingSuite::class.java.name} fails first started",
            "WARN ${LoggingSuite::class.java.name} fails first is about to fail",
        )
        val second = logs.getValue("fails second").joinToString("\n")
        second shouldContain "fails second is about to fail"
        second shouldNotContain "fails first"
    }

    @Test
    fun `a passing test publishes no report entry`() {
        run(selectClass(LoggingSuite::class.java)).testEvents().logs().keys shouldNotContain "passes quietly"
    }

    @Test
    fun `a line logged from a fork the test opened is the test's`() {
        run(selectClass(LoggingSuite::class.java)).testEvents().logs().getValue("fails after its fork logs") shouldBe
            listOf("INFO ${LoggingSuite::class.java.name} logged from a fork")
    }

    @Test
    fun `tests failed by a failed acquisition ran nothing and publish no report entry`() {
        run(selectClass(UnacquirableSuite::class.java)).testEvents().logs() shouldBe emptyMap()
    }

    @Test
    fun `a failing test's lines go to stdout, and a passing test's do not`() {
        val captured = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(captured, true))
        try {
            run(selectClass(LoggingSuite::class.java))
        } finally {
            System.setOut(stdout)
        }

        val printed = captured.toString()
        printed shouldContain "fails first is about to fail"
        printed shouldContain "logged from a fork"
        printed shouldNotContain "nobody should see this"
    }

    @Test
    fun `two tests running together count into metrics of their own`() {
        MeteredSuite.totals.clear()

        run(selectClass(MeteredSuite::class.java)).testEvents().succeeded().count() shouldBe 2L

        MeteredSuite.totals shouldContainExactly mapOf("counts one" to 1.0, "counts two" to 2.0)
    }

    @Test
    fun `two failing expectations are both reported, each at its own line, as one MultipleFailuresError`() {
        val failed = run(selectClass(ExpectingSuite::class.java)).testEvents().failureOf("fails both")

        val both = (failed as MultipleFailuresError).failures
        both.map { it::class } shouldBe listOf(AssertionFailedError::class, AssertionFailedError::class)
        both.map { it.message.orEmpty().lines().first() } shouldBe listOf(
            "expect(two == 3)",
            "expect(two + two == 5)",
        )
        both.map { it.stackTrace.first().fileName } shouldBe listOf("Samples.kt", "Samples.kt")
        withClue("the second failure is on the line after the first") {
            both[1].stackTrace.first().lineNumber shouldBe both[0].stackTrace.first().lineNumber + 1
        }
        failed.message.orEmpty() shouldContain "fails both: 2 of 2 expectations failed"
    }

    @Test
    fun `and, or and not pass and fail as their names say`() {
        val events = run(selectClass(ExpectingSuite::class.java))

        events.testEvents().succeeded().names() shouldContainExactlyInAnyOrder
            listOf("passes both", "passes either", "passes a negated failure")
        events.testEvents().failures().mapValues { (_, thrown) -> thrown::class } shouldContainExactly mapOf(
            "fails both" to MultipleFailuresError::class,
            "fails one of two" to AssertionFailedError::class,
            "fails either" to MultipleFailuresError::class,
            "fails a negated pass" to AssertionFailedError::class,
            "throws before it expects" to IllegalStateException::class,
            "fails unexplained" to AssertionFailedError::class,
        )
    }

    @Test
    fun `a single failed expectation is reported alone, under a heading that counts the expectations`() {
        val failed = run(selectClass(ExpectingSuite::class.java)).testEvents().failures()

        failed.getValue("fails one of two").message.orEmpty().lines().take(2) shouldBe listOf(
            "fails one of two: 1 of 2 expectations failed",
            "expect(two == 3)",
        )
        failed.getValue("fails a negated pass").message.orEmpty() shouldContain "not { expect(two == 2) }"
    }

    @Test
    fun `an expect called without power-assert says it was false and quotes the line that called it`() {
        val message = run(selectClass(ExpectingSuite::class.java)).testEvents().failureOf("fails unexplained").message

        message.orEmpty().lines().take(3) shouldBe listOf(
            "fails unexplained: 1 of 1 expectations failed",
            "expected: true",
            "but was:  false",
        )
        message.orEmpty().lines().last().trim() shouldBe
            "private val unexplained: (Boolean) -> Unit = ::expect"
    }

    @Test
    fun `two tests with one name are refused, because the name is the test's ID`() {
        val refused = shouldThrow<IllegalArgumentException> {
            object : Suite() {
                init {
                    test("twice") { expect(true) }
                    test("twice") { expect(true) }
                }
            }
        }

        refused.message.orEmpty() shouldContain "\"twice\""
    }
}
