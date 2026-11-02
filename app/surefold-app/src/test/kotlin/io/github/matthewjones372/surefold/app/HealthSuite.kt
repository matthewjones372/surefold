package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class Ledger
private class Lookup
private class Jammed
private class Endpoint(val health: HealthRegistry)

object HealthSuite : Suite() {
    init {
        test("a node can be handed the registry") {
            val module = single<Ledger> { Ledger() }.probe("ledger", timeout = 5.seconds) { _: Ledger -> true } +
                single { health: HealthRegistry -> Endpoint(health) }

            val readiness = testApp(module) { route: Endpoint -> route.health.readiness() }

            readiness shouldBe Health.Up
        }

        test("a critical probe that stops answering makes readiness Down") {
            val answering = AtomicBoolean(true)
            val module = single<Ledger> { Ledger() }
                .probe("ledger", timeout = 5.seconds) { _: Ledger -> answering.get() } +
                single { health: HealthRegistry -> Endpoint(health) }

            val readiness = testApp(module) { route: Endpoint ->
                answering.set(false)
                route.health.readiness()
            }

            readiness.shouldBeInstanceOf<Health.Down> { it.failing shouldBe listOf("ledger") }
        }

        test("a probe that is not critical makes readiness Degraded rather than Down") {
            val answering = AtomicBoolean(true)
            val module = single<Lookup> { Lookup() }
                .probe("lookup", timeout = 5.seconds, critical = false) { _: Lookup -> answering.get() } +
                single { health: HealthRegistry -> Endpoint(health) }

            val readiness = testApp(module) { route: Endpoint ->
                answering.set(false)
                route.health.readiness()
            }

            readiness.shouldBeInstanceOf<Health.Degraded> { it.failing shouldBe listOf("lookup") }
        }

        test("a critical probe that throws makes readiness Down, rather than throwing out of it") {
            val throwing = AtomicBoolean(false)
            val module = single<Ledger> { Ledger() }
                .probe("ledger", timeout = 5.seconds) { _: Ledger ->
                    check(!throwing.get()) { "connection is not available" }
                    true
                } +
                single { health: HealthRegistry -> Endpoint(health) }

            val readiness = testApp(module) { route: Endpoint ->
                throwing.set(true)
                route.health.readiness()
            }

            readiness.shouldBeInstanceOf<Health.Down> { it.failing shouldBe listOf("ledger") }
        }

        test("a probe that is not critical and throws makes readiness Degraded") {
            val throwing = AtomicBoolean(false)
            val module = single<Lookup> { Lookup() }
                .probe("lookup", timeout = 5.seconds, critical = false) { _: Lookup ->
                    check(!throwing.get()) { "lookup is unreachable" }
                    true
                } +
                single { health: HealthRegistry -> Endpoint(health) }

            val readiness = testApp(module) { route: Endpoint ->
                throwing.set(true)
                route.health.readiness()
            }

            readiness.shouldBeInstanceOf<Health.Degraded> { it.failing shouldBe listOf("lookup") }
        }

        test("a wedged probe answers inside its timeout rather than hanging") {
            val forever = CountDownLatch(1)
            val wedge = AtomicBoolean(false)
            val module = single<Jammed> { Jammed() }
                .probe("jammed", timeout = 50.milliseconds) { _: Jammed ->
                    if (wedge.get()) forever.await()
                    true
                } +
                single { health: HealthRegistry -> Endpoint(health) }

            val readiness = testApp(module) { route: Endpoint ->
                wedge.set(true)
                route.health.readiness()
            }

            readiness.shouldBeInstanceOf<Health.Down> { it.failing shouldBe listOf("jammed") }
        }

        test("liveness is Down until every node has started") {
            val duringStart = AtomicReference<Health>()
            val module = single<Ledger> { Ledger() }.probe("ledger", timeout = 5.seconds) { _: Ledger -> true } +
                single { health: HealthRegistry -> duringStart.set(health.liveness()); Endpoint(health) }

            val afterStart = testApp(module) { route: Endpoint -> route.health.liveness() }

            withClue("a node reading liveness while the graph is still coming up is told so") {
                duringStart.get().shouldNotBeNull { it.shouldBeInstanceOf<Health.Down>() }
            }
            afterStart shouldBe Health.Up
        }
    }
}
