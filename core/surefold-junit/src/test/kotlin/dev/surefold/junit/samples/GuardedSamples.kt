package dev.surefold.junit.samples

import dev.surefold.Limit
import dev.surefold.Resource
import dev.surefold.junit.Guarded
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.resource
import java.util.concurrent.atomic.AtomicInteger

/** How many tests hold a guard's resource at once, and the most that ever did. */
class Holders {
    private val now = AtomicInteger()
    val most = AtomicInteger()

    /** How many hold it with this one. */
    fun hold(): Int {
        val holding = now.incrementAndGet()
        most.accumulateAndGet(holding, ::maxOf)
        try {
            // Long enough for 50 tests that are let in at once to overlap.
            Thread.sleep(5)
        } finally {
            now.decrementAndGet()
        }
        return holding
    }
}

/** Lent to 3 tests at once; counts its acquisitions and releases across runs, and keeps the last run's holders. */
object CountedGuard : Guarded<Holders>(permits = 3) {
    val acquired = AtomicInteger()
    val released = AtomicInteger()

    @Volatile var last: Holders? = null

    override val resource: Resource<Holders> = resource {
        install(
            {
                Holders().also {
                    acquired.incrementAndGet()
                    last = it
                }
            },
        ) { _, _ ->
            released.incrementAndGet()
        }
    }
}

/** Never used by a suite, so a run never acquires it. */
object UnusedGuard : Guarded<Unit>(permits = 1) {
    val acquired = AtomicInteger()

    override val resource: Resource<Unit> = resource { acquired.incrementAndGet() }
}

/** One permit, so a test that kept it after failing would leave the next one waiting forever. */
object SinglePermitGuard : Guarded<Unit>(permits = 1) {
    override val resource: Resource<Unit> = resource {}
}

object BrokenGuard : Guarded<Unit>(permits = 1) {
    override val resource: Resource<Unit> = resource { error("the database did not start") }
}

/** 50 tests that may all run at once, leasing a guard that lets in 3. */
object LeasingSuite : SharedSuite<Holders>(limit = Limit.fixed(50)) {
    override val shared: Resource<Holders> = CountedGuard.lease()

    init {
        testEach("leases", 1..50) { holders, _ -> expect(holders.hold() <= 3) }
    }
}

/** The same guard from a plain suite, 50 tests that may all run at once. */
object UsingSuite : Suite(limit = Limit.fixed(50)) {
    init {
        testEach("uses", 1..50) { _ -> expect(CountedGuard.use { holders -> holders.hold() } <= 3) }
    }
}

object FailingLeaseSuite : SharedSuite<Unit>(limit = Limit.fixed(3)) {
    override val shared: Resource<Unit> = SinglePermitGuard.lease()

    init {
        testEach("fails", 1..3) { _, n -> error("failure $n") }
    }
}

object BrokenLeaseSuite : SharedSuite<Unit>() {
    override val shared: Resource<Unit> = BrokenGuard.lease()

    init {
        test("never gets the database") {}
    }
}
