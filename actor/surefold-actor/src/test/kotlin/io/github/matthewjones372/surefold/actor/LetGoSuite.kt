package io.github.matthewjones372.surefold.actor

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import kotlin.time.Duration.Companion.minutes

private sealed interface Brood

/** Spawns a child named [name], which stops itself on its first message. */
private data class Hatch(val name: String) : Brood

/** Tells the child [name] its first message, so that it stops. */
private data class Fledge(val name: String) : Brood

/** Stops the child [name] from the parent's own step, whether or not it has already ended. */
private data class Shoo(val name: String) : Brood

/** Stops a ref that is not a child at all. */
private data class Meddle(val ref: ActorRef<*>) : Brood

private fun leaving() = behaviour<Unit, Unit>(Unit) { _, _, _ -> stop() }

private fun nest() = behaviour<Brood, Map<String, ActorRef<Unit>>>(emptyMap()) { ctx, children, message ->
    when (message) {
        is Hatch -> become(children + (message.name to ctx.spawn(message.name, leaving())))

        is Fledge -> {
            children.getValue(message.name).tell(Unit)
            stay()
        }

        is Shoo -> {
            ctx.stop(children.getValue(message.name))
            stay()
        }

        is Meddle -> {
            ctx.stop(message.ref)
            stay()
        }
    }
}

private fun idle() = behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }

private sealed interface Kept

private data class Visit(val reply: Reply<Int>) : Kept

private fun kept() = behaviour<Kept, Int>(0) { _, visits, message ->
    when (message) {
        is Visit -> {
            message.reply(visits + 1)
            become(visits + 1)
        }
    }
}

object LetGoSuite : Suite() {
    init {
        test("a child that has stopped is no longer among its parent's children") {
            testActors {
                val parent = spawn("nest", nest())
                parent.send(Hatch("rex"))
                parent.send(Hatch("bo"))

                parent.send(Fledge("rex"))

                parent.children.map { it.address.path } shouldContainExactly listOf("/user/nest/bo")
            }
        }

        test("stopping a child that has already stopped does nothing, and stopping a stranger still fails") {
            testActors {
                val parent = spawn("nest", nest())
                val stranger = spawn("stranger", idle())
                parent.send(Hatch("rex"))
                parent.send(Fledge("rex"))

                parent.send(Shoo("rex"))
                parent.failure shouldBe null

                shouldThrow<IllegalArgumentException> { parent.send(Meddle(stranger)) }
            }
        }

        test("on threads, a child that has stopped is let go by its parent, and stopping it again does nothing") {
            val parent = scope {
                val parent = spawn("nest", nest())
                parent.tell(Hatch("rex"))
                parent.tell(Fledge("rex"))
                awaitIdle()
                parent.tell(Shoo("rex"))
                awaitIdle()
                parent.childCount
            }

            parent shouldBe 0
        }

        test("on threads, a scope that spawned and stopped a thousand actors holds none of them") {
            val held = scope {
                repeat(1_000) { stop(spawn("passing-$it", idle())).await() }
                actorCount
            }

            held shouldBe 0
        }

        test("on threads, an entity passivated a thousand times is one child of its manager") {
            val moving = TestClock()
            val seen = clock.locally(moving) {
                scope {
                    val kennel = spawn("kennel", entities(passivateAfter = 10.minutes) { kept() })
                    repeat(1_000) { _ ->
                        kennel.entity("rex").ask(1.minutes) { Visit(it) }
                        moving.adjust(10.minutes)
                        awaitIdle()
                    }
                    val visits = kennel.entity("rex").ask(1.minutes) { Visit(it) }
                    visits to kennel.childCount
                }
            }

            seen shouldBe (1.right() to 1)
        }
    }
}
