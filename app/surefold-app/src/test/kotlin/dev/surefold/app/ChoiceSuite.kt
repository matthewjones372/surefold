package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

object ChoiceSuite : Suite() {
    private enum class Kind {
        InProcess,
        Remote,
    }

    private interface Bus

    private class Hub(val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "hub"
        }
    }

    private class LocalBus(val hub: Hub) : Bus

    private class Producer(val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "producer"
        }
    }

    private class RemoteBus(val producer: Producer) : Bus

    private class Uses(val bus: Bus, val closed: MutableList<String>) : AutoCloseable {
        override fun close() {
            closed += "uses"
        }
    }

    // Each test passes a list of its own: the suite is one object, so a list in a field would be every test's.
    private fun bus(kind: Kind, closed: MutableList<String>): Module =
        single<Kind> { kind } +
            choice<Kind, Bus> { chosen ->
                when (chosen) {
                    Kind.InProcess -> closingOf { -> Hub(closed) } + single { hub: Hub -> LocalBus(hub) }.boundTo<Bus>()

                    Kind.Remote ->
                        closingOf { -> Producer(closed) } +
                            single { producer: Producer -> RemoteBus(producer) }.boundTo<Bus>()
                }
            } +
            closingOf { bus: Bus -> Uses(bus, closed) }

    init {
        test("the setting picks the branch, and only that branch is built") {
            val closed = CopyOnWriteArrayList<String>()
            testApp(bus(Kind.InProcess, closed)) { uses: Uses -> uses.bus.shouldBeInstanceOf<LocalBus>() }
            closed shouldBe listOf("uses", "hub")
        }

        test("overriding the setting picks the other branch") {
            val closed = CopyOnWriteArrayList<String>()
            val app = bus(Kind.InProcess, closed).overriding(single<Kind> { Kind.Remote })

            testApp(app) { uses: Uses -> uses.bus.shouldBeInstanceOf<RemoteBus>() }
            closed shouldBe listOf("uses", "producer")
        }

        test("before anything runs, the choice is a node providing its key") {
            val closed = CopyOnWriteArrayList<String>()
            bus(Kind.InProcess, closed).validate().isRight() shouldBe true
            bus(Kind.InProcess, closed).render() shouldContain "ChoiceSuite_Kind --> ChoiceSuite_Bus"
        }

        test("a branch that does not provide the key refuses the start, naming it") {
            val closed = CopyOnWriteArrayList<String>()
            val app =
                single<Kind> { Kind.InProcess } +
                    choice<Kind, Bus> { _ -> single<String> { "no bus" } } +
                    single { bus: Bus -> Uses(bus, closed) }

            val refused = app.use { _: Uses -> }.leftOrNull()

            refused.shouldBeInstanceOf<StartupError.Refused> { it.reason shouldContain "does not provide it" }
        }

        test("a branch that needs what nothing builds refuses the start") {
            val closed = CopyOnWriteArrayList<String>()
            val app =
                single<Kind> { Kind.InProcess } +
                    choice<Kind, Bus> { _ -> single { hub: Hub -> LocalBus(hub) }.boundTo<Bus>() } +
                    single { bus: Bus -> Uses(bus, closed) }

            val refused = app.use { _: Uses -> }.leftOrNull()

            refused.shouldBeInstanceOf<StartupError.Refused> { it.reason shouldContain "needs ChoiceSuite.Hub" }
        }

        test("a key the graph already holds is not replaced by the branch") {
            val closed = CopyOnWriteArrayList<String>()
            val app = bus(Kind.InProcess, closed).overriding(single<Bus> { object : Bus {} })

            testApp(app) { uses: Uses -> (uses.bus is LocalBus) shouldBe false }
            closed shouldBe listOf("uses")
        }

        test("a node only the chosen branch brings can be overridden") {
            val fake = Producer(CopyOnWriteArrayList())
            val app = bus(Kind.Remote, CopyOnWriteArrayList()).overriding(single<Producer> { fake })

            testApp(app) { uses: Uses -> (uses.bus as RemoteBus).producer shouldBe fake }
        }

        test("an override no chosen branch brings refuses the start") {
            val closed = CopyOnWriteArrayList<String>()
            val app = bus(Kind.InProcess, closed).overriding(single<Producer> { Producer(closed) })

            val refused = app.use { _: Uses -> }.leftOrNull()

            refused.shouldBeInstanceOf<StartupError.Refused> { it.reason shouldContain "no chosen branch provides it" }
        }

        test("a probe in the branch is asked like any other") {
            val closed = CopyOnWriteArrayList<String>()
            val app =
                single<Kind> { Kind.InProcess } +
                    choice<Kind, Bus> { _ ->
                        (closingOf { -> Hub(closed) } + single { hub: Hub -> LocalBus(hub) }.boundTo<Bus>()).probe(
                            "bus",
                            timeout = 1.seconds,
                        ) { _: Bus ->
                            false
                        }
                    } +
                    single { bus: Bus -> Uses(bus, closed) }

            val refused = app.use { _: Uses -> }.leftOrNull()

            refused.shouldBeInstanceOf<StartupError.Unready> { it.name shouldBe "bus" }
        }
    }
}
