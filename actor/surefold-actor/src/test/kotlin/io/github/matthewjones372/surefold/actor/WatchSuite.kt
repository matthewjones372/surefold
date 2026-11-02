package io.github.matthewjones372.surefold.actor

import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

private sealed interface Lookout

private data class Watch(val ref: ActorRef<*>) : Lookout

private data class Seen(val reply: Reply<Int>) : Lookout

/** Counts every `Terminated` it hears, and answers `Seen` with the count. */
private fun counted() = behaviour<Lookout, Int>(0) { ctx, heard, message ->
    when (message) {
        is Watch -> {
            ctx.watch(message.ref)
            stay()
        }

        is Seen -> {
            message.reply(heard)
            stay()
        }
    }
}.onSignal { _, heard, signal ->
    when (signal) {
        Signal.Stopping -> stay()
        is Signal.Terminated -> become(heard + 1)
    }
}

/** A ref to something that is not an actor on threads, which says when it has ended once [end] is called. */
private class Faraway : ActorRef<Unit>, Watchable {
    override val address = Address("far@127.0.0.1:1", "/user/far", 1)
    private val notified = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    override fun tell(message: Unit) = Unit

    override fun onTerminated(notify: () -> Unit) {
        notified += notify
    }

    fun end() = notified.forEach { it() }
}

private sealed interface Hand

private data object Leave : Hand

private data object Burst : Hand

private fun worker(stopping: AtomicReference<List<String>>) = behaviour<Hand, Unit>(Unit) { _, _, message ->
    when (message) {
        Leave -> stop()
        Burst -> error("burst")
    }
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) stopping.updateAndGet { it + "stopping" }
    stay()
}

object WatchSuite : Suite() {
    init {
        test("a watcher hears Terminated once, however often it watches") {
            testActors {
                val worker = spawn("worker", worker(AtomicReference(emptyList())))
                val lookout = spawn("lookout", counted())

                lookout.send(Watch(worker))
                lookout.send(Watch(worker))
                worker.send(Leave)

                lookout.state shouldBe 1
                lookout.signals shouldContainExactly listOf(Signal.Terminated(worker))
            }
        }

        test("watching an actor that has already stopped hears it at once") {
            testActors {
                val worker = spawn("worker", worker(AtomicReference(emptyList())))
                val lookout = spawn("lookout", counted())
                worker.send(Leave)

                lookout.send(Watch(worker))

                lookout.state shouldBe 1
            }
        }

        test("Stopping comes once before the actor ends, after a throw as after a stop") {
            val stopping = AtomicReference<List<String>>(emptyList())
            testActors {
                spawn("quits", worker(stopping)).send(Leave)
                shouldThrow<IllegalStateException> { spawn("bursts", worker(stopping)).send(Burst) }
            }

            stopping.get() shouldBe listOf("stopping", "stopping")
        }

        test("on threads, a watcher hears Terminated once") {
            val seen = scope {
                val worker = spawn("worker", worker(AtomicReference(emptyList())))
                val lookout = spawn("lookout", counted())
                lookout.tell(Watch(worker))
                lookout.tell(Watch(worker))
                lookout.ask(1.minutes) { Seen(it) }
                worker.tell(Leave)
                watch(worker).await()
                awaitIdle()
                lookout.ask(1.minutes) { Seen(it) }
            }

            seen shouldBeRight 1
        }

        test("on threads, watch from outside answers once the actor has stopped") {
            val stopping = AtomicReference<List<String>>(emptyList())
            val heard = scope {
                val worker = spawn("worker", worker(stopping))
                worker.tell(Leave)
                watch(worker).await()
            }

            heard.ref.address.path shouldBe "/user/worker"
            stopping.get() shouldBe listOf("stopping")
        }

        test("on threads, stop from outside ends the actor, and answers once it has") {
            val stopping = AtomicReference<List<String>>(emptyList())
            val heard = scope {
                val worker = spawn("worker", worker(stopping))
                stop(worker).await().ref shouldBe worker
                worker.ask(1.minutes) { _: Reply<Unit> -> Leave }
            }

            heard shouldBe AskFailure.Stopped.left()
            stopping.get() shouldBe listOf("stopping")
        }

        test(
            "on threads, a ref that is not an actor here can be watched, by an actor and by the scope, once it says so",
        ) {
            val far = Faraway()
            val seen = scope {
                val lookout = spawn("lookout", counted())
                lookout.tell(Watch(far))
                lookout.ask(1.minutes) { Seen(it) }
                val watched = watch(far)
                far.end()
                watched.await()
                awaitIdle()
                lookout.ask(1.minutes) { Seen(it) }
            }

            seen shouldBeRight 1
        }

        test("on threads, a letter a transport found reaches the scope's dead-letter handler") {
            val letters = java.util.concurrent.ConcurrentLinkedQueue<DeadLetter>()
            val far = Faraway()
            scope {
                onDeadLetter(letters::add)
                deadLetter(DeadLetter(far.address, "hello", DeadLetter.Why.Unreachable))
            }

            letters.toList() shouldContainExactly listOf(DeadLetter(far.address, "hello", DeadLetter.Why.Unreachable))
        }
    }
}
