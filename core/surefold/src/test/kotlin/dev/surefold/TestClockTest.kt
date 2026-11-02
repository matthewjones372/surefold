package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TestClockTest {

    private fun sleeping(clock: TestClock) {
        while (clock.sleepers() == 0) Thread.onSpinWait()
    }

    @Test
    fun `a test clock only moves when a test moves it`() {
        val moving = TestClock(Instant.EPOCH)

        moving.now() shouldBe Instant.EPOCH
        moving.adjust(60.minutes)

        moving.now() shouldBe Instant.EPOCH.plusSeconds(60 * 60)
    }

    @Test
    fun `a sleep does not return until the clock is moved past it`() {
        val moving = TestClock()
        val woke = AtomicBoolean()
        val sleeper = Thread { clock.locally(moving) { clock.get().sleep(60.minutes); woke.set(true) } }

        sleeper.start()
        sleeping(moving)

        withClue("the clock has not moved, so neither has the sleeper") { woke.get() shouldBe false }

        moving.adjust(60.minutes)
        sleeper.join(5_000)

        woke.get() shouldBe true
    }

    @Test
    fun `nothing has happened before the recurrence, and one thing has after each`() {
        val moving = TestClock()
        val ticks = AtomicInteger()

        val ran = clock.locally(moving) {
            scope {
                val poller = async {
                    repeat(2) { clock.get().sleep(60.minutes); ticks.incrementAndGet() }
                    ticks.get()
                }
                moving.adjustWhenBlocked(60.minutes)
                // Returns only once something is asleep again, which the first tick had to precede.
                moving.adjustWhenBlocked(60.minutes)
                poller.await()
            }
        }

        ran shouldBe 2
    }

    @Test
    fun `setting the time releases every sleep it passes`() {
        val moving = TestClock()
        val woke = AtomicInteger()
        val sleepers = (1..3).map {
            Thread { clock.locally(moving) { clock.get().sleep(it.minutes); woke.incrementAndGet() } }
        }

        sleepers.forEach(Thread::start)
        while (moving.sleepers() < 3) Thread.onSpinWait()
        moving.setTime(Instant.EPOCH.plusSeconds(10 * 60))
        sleepers.forEach { it.join(5_000) }

        woke.get() shouldBe 3
    }

    @Test
    fun `a sleep of nothing does not wait to be released`() {
        val moving = TestClock()

        clock.locally(moving) { clock.get().sleep(kotlin.time.Duration.ZERO) }

        moving.sleepers() shouldBe 0
    }

    @Test
    fun `a move stops at every instant a waiter asks for, in order, and lets it settle there`() {
        val moving = TestClock()
        val settledAt = mutableListOf<Instant>()
        val wakes = ArrayDeque(listOf(10, 25, 40).map { Instant.EPOCH.plusSeconds(it.toLong()) })
        val waiter = object : TestClock.Waiter {
            override fun wakesAt(): Instant? = wakes.firstOrNull()

            override fun settle() {
                settledAt += moving.now()
                if (wakes.firstOrNull()?.let { it <= moving.now() } == true) wakes.removeFirst()
            }
        }

        val registered = moving.register(waiter)
        moving.adjust(30.seconds)

        settledAt shouldBe listOf(10, 25, 30).map { Instant.EPOCH.plusSeconds(it.toLong()) }
        moving.now() shouldBe Instant.EPOCH.plusSeconds(30)

        registered.close()
        moving.adjust(30.seconds)
        withClue("a waiter let go of is not stopped for") { settledAt.size shouldBe 3 }
    }
}
