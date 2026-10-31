package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.fail.Either
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import kotlin.time.Duration.Companion.seconds

@OptIn(TurnSeam::class)
class HandoffDecisionTest {

    @Test
    fun `pending looks stay pending`() {
        val decision = HandoffDecision<Any>()
        decision.peek() shouldBe null
        decision.peek() shouldBe null
    }

    @Test
    fun `only the first value completes and its exact reference remains`() {
        val decision = HandoffDecision<Any>()
        val first = Any()
        decision.tryComplete(first) shouldBe true
        decision.tryComplete(Any()) shouldBe false
        decision.tryComplete(first) shouldBe false
        (decision.peek() === first) shouldBe true
        (decision.peek() === first) shouldBe true
    }

    @Test
    fun `completion and looking never ask a payload for equality or hashing`() {
        val calls = Shared(0 to 0)
        val decision = HandoffDecision<EqualityProbe>()
        val first = EqualityProbe(calls)
        val later = EqualityProbe(calls)
        decision.tryComplete(first) shouldBe true
        decision.tryComplete(later) shouldBe false
        (decision.peek() === first) shouldBe true
        calls.peek() shouldBe (0 to 0)
    }

    @Test
    fun `a Java null fails before changing the pending decision`() {
        val decision = HandoffDecision<Any>()
        val complete = HandoffDecision::class.java.getMethod("tryComplete", Any::class.java)
        val failure = shouldThrow<InvocationTargetException> { complete.invoke(decision, null) }
        (failure.cause is NullPointerException) shouldBe true
        decision.peek() shouldBe null
        decision.tryComplete(Any()) shouldBe true
    }

    @Test
    fun `an interrupted winner keeps its interrupt through completion and looking`() {
        val decision = HandoffDecision<Any>()
        val value = Any()
        interruptedAttempt(decision, value, value) shouldBe Triple(true, true, true)
    }

    @Test
    fun `an interrupted loser keeps its interrupt through refusal and looking`() {
        val decision = HandoffDecision<Any>()
        val first = Any()
        decision.tryComplete(first) shouldBe true
        interruptedAttempt(decision, Any(), first) shouldBe Triple(false, true, true)
    }

    @Test
    fun `committing and looking stay quiet when every metrics lookup throws`() {
        val decision = HandoffDecision<Any>()
        val value = Any()
        val other = Any()
        val pending = metrics.locally(ThrowingMetrics) { decision.peek() }
        val completed = Either.catch { metrics.locally(ThrowingMetrics) { decision.tryComplete(value) } }
        val published = metrics.locally(ThrowingMetrics) { decision.peek() }
        pending shouldBe null
        (published === value) shouldBe true
        completed shouldBe Either.Right(true)
        val later = metrics.locally(ThrowingMetrics) { decision.tryComplete(other) }
        val remaining = metrics.locally(ThrowingMetrics) { decision.peek() }
        later shouldBe false
        (remaining === value) shouldBe true
    }

    @Test
    fun `completion and looking never inspect bound metric tags`() {
        val decision = HandoffDecision<Any>()
        val value = Any()
        val other = Any()
        val pending = metricTags.locally(ThrowingTags) { decision.peek() }
        val completed = metricTags.locally(ThrowingTags) { decision.tryComplete(value) }
        val later = metricTags.locally(ThrowingTags) { decision.tryComplete(other) }
        val published = metricTags.locally(ThrowingTags) { decision.peek() }
        pending shouldBe null
        completed shouldBe true
        later shouldBe false
        (published === value) shouldBe true
    }

    @Test
    fun `completion and looking never notify the retry cause observer`() {
        val decision = HandoffDecision<Any>()
        val value = Any()
        val other = Any()
        val observer = Retried { _, _ -> throw observerFailed }
        val pending = retried.locally(observer) { decision.peek() }
        val completed = retried.locally(observer) { decision.tryComplete(value) }
        val later = retried.locally(observer) { decision.tryComplete(other) }
        val published = retried.locally(observer) { decision.peek() }
        pending shouldBe null
        completed shouldBe true
        later shouldBe false
        (published === value) shouldBe true
    }

    @Test
    fun `completion and looking never enter the turn seam`() {
        val decision = HandoffDecision<Any>()
        val value = Any()
        val other = Any()
        val pending = takingTurns(ThrowingTurns) { decision.peek() }
        val completed = takingTurns(ThrowingTurns) { decision.tryComplete(value) }
        val later = takingTurns(ThrowingTurns) { decision.tryComplete(other) }
        val published = takingTurns(ThrowingTurns) { decision.peek() }
        pending shouldBe null
        completed shouldBe true
        later shouldBe false
        (published === value) shouldBe true
    }

    @Test
    fun `competing completers agree on exactly one winning reference`() {
        clock.locally(SystemClock) {
            timeout(10.seconds) {
                scope {
                    val decision = HandoffDecision<Any>()
                    val values = List(8) { Any() }
                    val ready = List(8) { Promise<Nothing, Unit>() }
                    val start = Promise<Nothing, Unit>()
                    resourceScope {
                        onRelease { _ -> start.succeed(Unit) }
                        val contenders = values.mapIndexed { index, value ->
                            async {
                                ready[index].succeed(Unit)
                                start.awaitValue()
                                decision.tryComplete(value)
                            }
                        }
                        ready.forEach { it.awaitValue() }
                        start.succeed(Unit)
                        val answers = contenders.map { it.await() }
                        answers.count { it } shouldBe 1
                        (decision.peek() === values[answers.indexOf(true)]) shouldBe true
                    }
                }
            }
        }
    }

    @Test
    fun `an observer sees payload writes made before the winning completion`() {
        val decision = HandoffDecision<PublishedValue>()
        val value = PublishedValue()
        val ready = Promise<Nothing, Unit>()
        scope {
            val observer = async {
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        ready.succeed(Unit)
                        var seen: PublishedValue?
                        do {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            seen = decision.peek()
                            if (seen == null) Thread.onSpinWait()
                        } while (seen == null)
                        seen.number
                    }
                }
            }
            ready.awaitValue()
            value.number = 42
            decision.tryComplete(value) shouldBe true
            observer.await() shouldBe 42
        }
    }
}

private fun interruptedAttempt(
    decision: HandoffDecision<Any>,
    value: Any,
    expected: Any,
): Triple<Boolean, Boolean, Boolean> =
    scope {
        async {
            val thread = Thread.currentThread()
            val alreadyInterrupted = thread.isInterrupted
            try {
                thread.interrupt()
                val accepted = decision.tryComplete(value)
                val same = decision.peek() === expected
                Triple(accepted, same, thread.isInterrupted)
            } finally {
                if (!alreadyInterrupted) Thread.interrupted()
            }
        }.await()
    }

private class PublishedValue(var number: Int = 0)

private class EqualityProbe(private val calls: Shared<Pair<Int, Int>>) {
    override fun equals(other: Any?): Boolean {
        calls.update { (it.first + 1) to it.second }
        return this === other
    }

    override fun hashCode(): Int {
        calls.update { it.first to (it.second + 1) }
        return 0
    }
}

private class ObserverFailed : RuntimeException("unexpected decision observer")

private val observerFailed = ObserverFailed()

private object ThrowingMetrics : Metrics {
    override fun counter(name: String, tags: Map<String, String>): Counter = throw observerFailed

    override fun gauge(name: String, tags: Map<String, String>): Gauge = throw observerFailed

    override fun histogram(name: String, tags: Map<String, String>): Histogram = throw observerFailed

    override fun read(name: String): List<Reading> = throw observerFailed
}

private object ThrowingTags : Map<String, String> by emptyMap() {
    override val entries: Set<Map.Entry<String, String>> get() = throw observerFailed

    override val size: Int get() = throw observerFailed
}

@OptIn(TurnSeam::class)
private object ThrowingTurns : Turns {
    override fun forking(): Any = throw observerFailed

    override fun started(ticket: Any): Unit = throw observerFailed

    override fun ended(): Unit = throw observerFailed

    override fun parking(): Boolean = throw observerFailed

    override fun woke(): Unit = throw observerFailed

    override fun waking(thread: Thread): Unit = throw observerFailed
}
