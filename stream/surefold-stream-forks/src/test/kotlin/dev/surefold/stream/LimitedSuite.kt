package dev.surefold.stream

import dev.surefold.Limit
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val SETTLE_SECONDS = 30L

/** Streams under a [Limit] (spec 0171): one limit bounds every stream that draws on it, together. */
object LimitedSuite : Suite() {
    init {
        test("two streams sharing one limit never have more inside it, between them, than it allows") {
            val dependency = Limit.fixed(3)
            val now = AtomicInteger()
            val most = AtomicInteger()
            fun reads(): Running<Nothing, Int> = Stream.from(1..100)
                .mapPar(dependency) { n ->
                    most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                    Thread.sleep(1)
                    now.decrementAndGet()
                    n
                }
                .runFold(0) { sum, n -> sum + n }
                .start(Forks())

            val first = reads()
            val second = reads()

            val firstExit = first.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
            val secondExit = second.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
            firstExit shouldBe Exit.Done((1..100).sum())
            secondExit shouldBe Exit.Done((1..100).sum())
            withClue("the most calls inside the shared limit at once") { (most.get() <= 3) shouldBe true }
            dependency.inFlight shouldBe 0
        }
    }
}
