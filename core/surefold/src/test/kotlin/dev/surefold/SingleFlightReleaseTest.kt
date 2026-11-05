package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.Either
import org.junit.jupiter.api.Test

class SingleFlightReleaseTest {
    @Test
    fun `release without workers still closes admission`() {
        val flights = SingleFlight<String, Nothing, Int>()
        flights.closeAndJoin()
        shouldThrow<IllegalStateException> { flights.run("k") { Either.Right(1) } }
        flights.closeAndJoin()
    }

    @Test
    fun `release waits for every worker finally and rejects new admission`() {
        val flights = SingleFlight<String, Nothing, Int>()
        val entered = listOf(Promise<Nothing, Unit>(), Promise<Nothing, Unit>())
        val cleaning = listOf(Promise<Nothing, Unit>(), Promise<Nothing, Unit>())
        val finish = Promise<Nothing, Unit>()
        val workers = Shared<List<Thread>>(emptyList())
        val callers =
            (0..1).map { key ->
                surefoldFuture {
                    shouldThrow<InterruptedException> {
                        flights.run("$key") {
                            workers.update { it + Thread.currentThread() }
                            entered[key].succeed(Unit)
                            try {
                                Promise<Nothing, Unit>().awaitValue()
                                Either.Right(key)
                            } finally {
                                cleaning[key].succeed(Unit)
                                finish.awaitIgnoringInterrupts()
                            }
                        }
                    }
                }
            }
        entered.forEach { it.awaitValue() }
        val waiter = surefoldFuture {
            shouldThrow<InterruptedException> { flights.run("0") { Either.Right(99) } }
        }
        while (flights.waiting("0") < 2) Thread.onSpinWait()
        val released = Promise<Nothing, Boolean>()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            flights.closeAndJoin()
            released.succeed(Thread.currentThread().isInterrupted)
        }
        try {
            cleaning.forEach { it.awaitValue() }
            closer.awaitValue().interrupt()
            closer.awaitValue().awaitParkedOrTerminated()
            released.peek() shouldBe null
            shouldThrow<IllegalStateException> { flights.run("new") { Either.Right(3) } }
            shouldThrow<IllegalStateException> { flights.run("0") { Either.Right(3) } }
        } finally {
            finish.succeed(Unit)
            close.get()
        }
        workers.peek().all { !it.isAlive } shouldBe true
        released.awaitValue() shouldBe true
        callers.forEach { it.get() }
        waiter.get()
        flights.inFlight() shouldBe 0
        flights.closeAndJoin()
    }

    @Test
    fun `a cancellation resistant lookup finishes and answers its existing waiter before release`() {
        val flights = SingleFlight<String, Nothing, Int>()
        val entered = Promise<Nothing, Thread>()
        val cancelled = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val caller = surefoldFuture {
            flights.run("k") {
                entered.succeed(Thread.currentThread())
                try {
                    Promise<Nothing, Unit>().awaitValue()
                } catch (_: InterruptedException) {
                    cancelled.succeed(Unit)
                    finish.awaitIgnoringInterrupts()
                }
                Either.Right(7)
            }
        }
        val worker = entered.awaitValue()
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            flights.closeAndJoin()
        }
        try {
            cancelled.awaitValue()
            closer.awaitValue().awaitParkedOrTerminated()
            close.isDone shouldBe false
            caller.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            close.get()
        }
        caller.get() shouldBe Either.Right(7)
        worker.isAlive shouldBe false
    }

    @Test
    fun `concurrent releases wait for the same resistant cleanup`() {
        val flights = SingleFlight<String, Nothing, Int>()
        val entered = Promise<Nothing, Unit>()
        val cleaning = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val caller = surefoldFuture {
            shouldThrow<InterruptedException> {
                flights.run("k") {
                    entered.succeed(Unit)
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                        Either.Right(1)
                    } finally {
                        cleaning.succeed(Unit)
                        finish.awaitIgnoringInterrupts()
                    }
                }
            }
        }
        entered.awaitValue()
        val firstCloser = Promise<Nothing, Thread>()
        val first = surefoldFuture {
            firstCloser.succeed(Thread.currentThread())
            flights.closeAndJoin()
        }
        cleaning.awaitValue()
        val secondCloser = Promise<Nothing, Thread>()
        val second = surefoldFuture {
            secondCloser.succeed(Thread.currentThread())
            flights.closeAndJoin()
        }
        try {
            firstCloser.awaitValue().awaitParkedOrTerminated()
            secondCloser.awaitValue().awaitParkedOrTerminated()
            first.isDone shouldBe false
            second.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            first.get()
            second.get()
            caller.get()
        }
    }

    @Test
    fun `an interrupted release finishes joining and restores the interrupt`() {
        val flights = SingleFlight<String, Nothing, Int>()
        val entered = Promise<Nothing, Unit>()
        val cleaning = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val worker = Promise<Nothing, Thread>()
        val caller = surefoldFuture {
            shouldThrow<InterruptedException> {
                flights.run("k") {
                    worker.succeed(Thread.currentThread())
                    entered.succeed(Unit)
                    try {
                        Promise<Nothing, Unit>().awaitValue()
                        Either.Right(1)
                    } finally {
                        cleaning.succeed(Unit)
                        finish.awaitIgnoringInterrupts()
                    }
                }
            }
        }
        entered.awaitValue()
        val releaseThread = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            releaseThread.succeed(Thread.currentThread())
            Thread.currentThread().interrupt()
            flights.closeAndJoin()
            Thread.currentThread().isInterrupted
        }
        try {
            cleaning.awaitValue()
            releaseThread.awaitValue().interrupt()
            releaseThread.awaitValue().awaitParkedOrTerminated()
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
        }
        close.get() shouldBe true
        worker.awaitValue().isAlive shouldBe false
        caller.get()
    }

    @Test
    fun `a worker cannot close itself from its lookup or finally`() {
        val flights = SingleFlight<String, Nothing, Int>()
        flights.run("k") {
            shouldThrow<IllegalStateException> { flights.closeAndJoin() }
            try {
                Either.Right(1)
            } finally {
                shouldThrow<IllegalStateException> { flights.closeAndJoin() }
            }
        } shouldBe Either.Right(1)
        flights.run("k") { Either.Right(2) } shouldBe Either.Right(2)
        flights.closeAndJoin()
    }

    @Test
    fun `release does not report worker outcomes a second time`() {
        val flights = SingleFlight<String, String, Int>()
        flights.run("k") { Either.Left("source unavailable") } shouldBe Either.Left("source unavailable")
        shouldThrow<IllegalArgumentException> { flights.run("k") { throw IllegalArgumentException("broken") } }
        flights.closeAndJoin()
    }

    @Test
    fun `cancelAll remains reusable before awaited release`() {
        val flights = SingleFlight<String, Nothing, Int>()
        val entered = Promise<Nothing, Unit>()
        val caller = surefoldFuture {
            shouldThrow<InterruptedException> {
                flights.run("k") {
                    entered.succeed(Unit)
                    Promise<Nothing, Unit>().awaitValue()
                    Either.Right(1)
                }
            }
        }
        entered.awaitValue()
        flights.cancelAll()
        caller.get()
        flights.run("k") { Either.Right(2) } shouldBe Either.Right(2)
        flights.closeAndJoin()
    }

    @Test
    fun `cancelAll cannot miss a worker whose flight has just been admitted`() {
        repeat(200) {
            val flights = SingleFlight<String, Nothing, Int>()
            parZip(
                {
                    shouldThrow<InterruptedException> {
                        flights.run("k") {
                            Promise<Nothing, Unit>().awaitValue()
                            Either.Right(1)
                        }
                    }
                },
                {
                    while (flights.inFlight() == 0) Thread.onSpinWait()
                    flights.cancelAll()
                },
            ) { _, _ ->
            }
            flights.run("k") { Either.Right(2) } shouldBe Either.Right(2)
            flights.closeAndJoin()
        }
    }

    @Test
    fun `release joins accepted workers when startup races closed admission`() {
        repeat(200) {
            val flights = SingleFlight<String, Nothing, Int>()
            val worker = Shared<Thread?>(null)
            parZip(
                {
                    try {
                        flights.run("k") {
                            worker.update { Thread.currentThread() }
                            Promise<Nothing, Unit>().awaitValue()
                            Either.Right(1)
                        }
                    } catch (_: InterruptedException) {
                        // Accepted work was cancelled by its owner.
                    } catch (_: IllegalStateException) {
                        // Closed admission refused this lookup.
                    }
                },
                { flights.closeAndJoin() },
            ) { _, _ ->
            }
            (worker.peek()?.isAlive ?: false) shouldBe false
            flights.inFlight() shouldBe 0
        }
    }

    @Test
    fun `release joins replaced workers even after their answers have been published`() {
        val flights = SingleFlight<String, Nothing, Thread>("publication-flight")
        val workers = Shared<List<Thread>>(emptyList())
        val publishing = Shared<List<Thread>>(emptyList())
        val finish = Promise<Nothing, Unit>()
        val publicationMetrics =
            object : Metrics by NoMetrics {
                override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
                    if (tags["transact"] == "publication-flight" && Thread.currentThread() in workers.peek()) {
                        metrics.locally(NoMetrics) {
                            shouldThrow<IllegalStateException> { flights.closeAndJoin() }
                            publishing.update { it + Thread.currentThread() }
                            finish.awaitIgnoringInterrupts()
                        }
                    }
                }
            }
        metrics.locally(publicationMetrics) {
            repeat(2) {
                flights.run("k") {
                    workers.update { it + Thread.currentThread() }
                    Either.Right(Thread.currentThread())
                }
            }
        }
        publishing.awaitUntil { it.size == 2 }
        flights.inFlight() shouldBe 0
        val closer = Promise<Nothing, Thread>()
        val close = surefoldFuture {
            closer.succeed(Thread.currentThread())
            flights.closeAndJoin()
        }
        try {
            closer.awaitValue().awaitParkedOrTerminated()
            close.isDone shouldBe false
        } finally {
            finish.succeed(Unit)
            close.get()
        }
        workers.peek().all { !it.isAlive } shouldBe true
    }

    @Test
    fun `shared work keeps the first callers bindings`() {
        val flights = SingleFlight<String, Nothing, String>()
        val binding = surefoldLocal { "unbound" }
        val entered = Promise<Nothing, Unit>()
        val finish = Promise<Nothing, Unit>()
        val first = surefoldFuture {
            binding.locally("first") {
                flights.run("k") {
                    entered.succeed(Unit)
                    finish.awaitValue()
                    Either.Right(binding.get())
                }
            }
        }
        entered.awaitValue()
        val second = surefoldFuture { binding.locally("second") { flights.run("k") { Either.Right(binding.get()) } } }
        try {
            while (flights.waiting("k") < 2) Thread.onSpinWait()
        } finally {
            finish.succeed(Unit)
        }
        first.get() shouldBe Either.Right("first")
        second.get() shouldBe Either.Right("first")
        flights.closeAndJoin()
    }
}

private fun Promise<Nothing, Unit>.awaitIgnoringInterrupts() {
    while (true) {
        try {
            awaitValue()
            return
        } catch (_: InterruptedException) {
            // Cancellation-resistant cleanup still owns its dependency until explicitly released.
        }
    }
}

private fun Thread.awaitParkedOrTerminated() {
    while (isAlive && state != Thread.State.WAITING) Thread.onSpinWait()
}
