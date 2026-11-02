package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldThrow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.seconds

private class Hub
private class Spoke

/** Spec 0129: what a node does once the whole graph is built, and a probe asked only then. */
object StartedSuite : Suite() {
    init {
        test(
            "started hooks run once every node is built, in the graph's order, and a WhenStarted probe after them",
        ) {
            val log = ConcurrentLinkedQueue<String>()
            val module = single<Hub> {
                log += "hub built"
                onStarted { log += "hub started" }
                Hub()
            }.probe("hub", timeout = 5.seconds, asked = Asked.WhenStarted) { _: Hub -> log += "hub asked"; true } +
                single { _: Hub ->
                    log += "spoke built"
                    onStarted { log += "spoke started" }
                    Spoke()
                }

            module.use { _: Spoke -> log += "running" }.getOrNull().shouldNotBeNull()

            log.toList() shouldContainExactly
                listOf("hub built", "spoke built", "hub started", "spoke started", "hub asked", "running")
        }

        test("a WhenStarted probe that never passes fails the start, naming its node, after every node is built") {
            val built = ConcurrentLinkedQueue<String>()
            val module = single<Hub> { Hub() }
                .probe("hub", timeout = 5.seconds, asked = Asked.WhenStarted) { _: Hub -> false } +
                single { _: Hub -> built += "spoke"; Spoke() }

            module.use { _: Spoke -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Unready> { unready -> unready.name shouldBe "hub" }
                built.toList() shouldContainExactly listOf("spoke")
            }
        }

        test(
            "a started hook that throws fails the start as its node's refusal, and one that refuses keeps its reason",
        ) {
            val thrown = single<Hub> { onStarted { error("no broker") }; Hub() }.use { _: Hub -> }.leftOrNull()

            thrown.shouldBeInstanceOf<StartupError.Refused> { refused ->
                refused.key shouldBe typeOf<Hub>()
                refused.reason shouldBe "its onStarted threw IllegalStateException: no broker"
            }

            val declined = single<Hub> { onStarted { refuse("not today") }; Hub() }.use { _: Hub -> }.leftOrNull()

            declined shouldBe StartupError.Refused(typeOf<Hub>(), "not today")
        }

        test("a started hook asked for once the graph has started throws, rather than never running") {
            var wiring: Wiring? = null
            val module = single<Hub> { wiring = this; Hub() }

            module.use { _: Hub ->
                wiring.shouldNotBeNull { started -> shouldThrow<IllegalStateException>({ started.onStarted { } }) }
            } shouldBeRight Unit
        }
    }
}
