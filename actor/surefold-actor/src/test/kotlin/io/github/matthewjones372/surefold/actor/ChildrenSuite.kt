package io.github.matthewjones372.surefold.actor

import dev.surefold.Schedule
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.scope
import java.util.concurrent.atomic.AtomicReference

private sealed interface Shift

private data class Hire(val name: String) : Shift

private data object Shut : Shift

private data object Trip : Shift

private data object Rest

/** Records its own Stopping in [log], under [name]. */
private fun clerk(name: String, log: AtomicReference<List<String>>) = behaviour<Rest, Unit>(Unit) { _, _, _ ->
    stay()
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) log.updateAndGet { it + name }
    stay()
}

/** Hires clerks as children; `Shut` stops it, `Trip` fails it. */
private fun manager(log: AtomicReference<List<String>>) = behaviour<Shift, Unit, String>(Unit) { ctx, _, message ->
    when (message) {
        is Hire -> {
            ctx.spawn(message.name, clerk(message.name, log))
            stay()
        }

        Shut -> stop()

        Trip -> fail("tripped")
    }
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) log.updateAndGet { it + "manager" }
    stay()
}

object ChildrenSuite : Suite() {
    init {
        test("a child's Stopping comes before its parent's") {
            val log = AtomicReference<List<String>>(emptyList())
            testActors {
                val manager = spawn("manager", manager(log))
                manager.send(Hire("ann"))
                manager.send(Hire("bob"))

                manager.children.map { it.address.path } shouldBe
                    listOf("/user/manager/ann", "/user/manager/bob")
                manager.send(Shut)
            }

            log.get() shouldBe listOf("ann", "bob", "manager")
        }

        test("a parent's restart stops its children first, and the parent carries on") {
            val log = AtomicReference<List<String>>(emptyList())
            testActors {
                val manager = spawn("manager", manager(log), restart = Schedule.recurs(1))
                manager.send(Hire("ann"))

                manager.send(Trip)

                manager.stopped shouldBe false
                manager.children shouldBe emptyList()
            }

            log.get() shouldBe listOf("ann")
        }

        test("on threads, a child's Stopping comes before its parent's") {
            val log = AtomicReference<List<String>>(emptyList())
            scope {
                val manager = spawn("manager", manager(log))
                manager.tell(Hire("ann"))
                manager.tell(Shut)
                watch(manager).await()
            }

            log.get() shouldBe listOf("ann", "manager")
        }

        test("on threads, closing the scope stops a child before its parent") {
            val log = AtomicReference<List<String>>(emptyList())
            scope {
                spawn("manager", manager(log)).tell(Hire("ann"))
                awaitIdle()
            }

            log.get() shouldBe listOf("ann", "manager")
        }

        test("on threads, a parent's restart stops its children first") {
            val log = AtomicReference<List<String>>(emptyList())
            scope {
                val manager = spawn("manager", manager(log), restart = Schedule.recurs(1))
                manager.tell(Hire("ann"))
                manager.tell(Trip)
                awaitIdle()
                log.get() shouldBe listOf("ann")
            }
        }
    }
}
