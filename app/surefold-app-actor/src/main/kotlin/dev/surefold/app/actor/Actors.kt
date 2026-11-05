package dev.surefold.app.actor

import dev.surefold.Deferred
import dev.surefold.Promise
import dev.surefold.Schedule
import dev.surefold.Scope
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.DeadLetter
import dev.surefold.actor.Failure
import dev.surefold.actor.ServiceKey
import dev.surefold.actor.Signal
import dev.surefold.actor.awaitIdle
import dev.surefold.actor.find
import dev.surefold.actor.onDeadLetter
import dev.surefold.actor.spawn
import dev.surefold.actor.stop
import dev.surefold.actor.watch
import dev.surefold.app.Module
import dev.surefold.app.Wiring
import dev.surefold.app.single
import dev.surefold.fail.fold
import dev.surefold.logDebug
import dev.surefold.logError
import dev.surefold.scope
import dev.surefold.surefoldFuture
import dev.surefold.transact
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * A scope held open for the application's life, for its actors to live in. It is opened on a thread of its own that
 * waits until the node is released, and releasing it closes the scope: its close hooks run first, and then every actor
 * stops after its running step. Everything here is the scope's own call.
 */
class Actors
internal constructor(
    private val scope: Scope<Nothing>,
    private val tasks: Tasks,
    // The thread holding the scope open.
    private val holder: Thread,
) {

    fun <M : Any, S, E> spawn(
        name: String,
        behaviour: Behaviour<M, S, E>,
        capacity: Int = 1024,
        throughput: Int = 64,
        restart: Schedule<Failure<E>, *>? = null,
        stash: Int = 1024,
    ): ActorRef<M> = scope.spawn(name, behaviour, capacity, throughput, restart, stash)

    fun stop(ref: ActorRef<*>): Signal.Terminated = scope.stop(ref).await()

    fun watch(ref: ActorRef<*>): Deferred<Signal.Terminated> = scope.watch(ref)

    fun <M : Any> find(key: ServiceKey<M>): Set<ActorRef<M>> = scope.find(key)

    fun awaitIdle() = scope.awaitIdle()

    /**
     * Runs [block] on the scope's own thread and answers what it returned, or throws what it threw: for what must be
     * started in the scope itself, such as a remote node or a cluster. What it starts closes with the scope, through
     * its close hooks, so a cluster made here leaves before the application's actors stop (spec 0080). Asked from the
     * scope's own thread, as from inside another [within], it runs [block] there and then.
     */
    fun <A> within(block: Scope<Nothing>.() -> A): A {
        if (Thread.currentThread() === holder) return scope.block()
        val task = FutureTask { scope.block() }
        // Checked and queued in the one step close changes, so no task is queued behind Close and never run.
        tasks.offer(task)
        return try {
            task.get()
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    // What holds the scope open, answered once it has closed.
    internal lateinit var holding: CompletableFuture<Unit>

    /** Lets the scope close, and waits for it to have: every actor has stopped once this returns. */
    internal fun close() {
        tasks.close()
        // A scope that failed as it closed was logged by the hook below; releasing the node goes on regardless.
        holding.handle { _, _ -> }.join()
    }
}

/** What `within` hands the scope's thread, and whether the scope still takes any: changed together, in one step. */
internal class Tasks {
    private val held = Shared(emptyList<Runnable>() to false, name = "surefold.app.actors")

    /** Queues [task]; refused once the scope has closed. */
    fun offer(task: Runnable) = held.update { (queue, closed) ->
        check(!closed) { "the actors' scope has closed" }
        queue + task to false
    }

    /** Queues [Close] last, and refuses every task after it. */
    fun close() = held.update { (queue, _) -> queue + Close to true }

    /** The oldest task, parking until there is one. */
    fun take(): Runnable =
        transact(held.name) {
            val (queue, closed) = held.get()
            if (queue.isEmpty()) waitForChange()
            held.set(queue.drop(1) to closed)
            queue.first()
        }
}

/** What the scope's thread takes to stop running tasks and let the scope close. */
private val Close = Runnable {}

/** The [Actors] node. [onDeadLetter] is handed every dead letter of its actors; by default each is logged at debug. */
fun actors(onDeadLetter: (DeadLetter) -> Unit = { logDebug("dead letter: $it") }): Module =
    single<Actors> {
        install({ open(onDeadLetter) }) { actors, _ -> actors.close() }
    }

/**
 * Opens a scope on a virtual thread of its own, with every binding of the thread that asked, as a fork has them, and
 * hands it back once it stands: a thread the scope did not fork would inherit none of them.
 */
private fun open(onDeadLetter: (DeadLetter) -> Unit): Actors {
    val opened = Promise<Throwable, Actors>("surefold.app.actors.opened")
    val tasks = Tasks()
    val holding = surefoldFuture {
        scope {
            // The guardian stands up here, on the scope's own thread, before any spawn from elsewhere.
            onDeadLetter(onDeadLetter)
            opened.succeed(Actors(this, tasks, Thread.currentThread()))
            // The scope's thread runs what `within` hands it until the node is released.
            generateSequence { tasks.take() }.takeWhile { it !== Close }.forEach(Runnable::run)
        }
    }
        .whenComplete { _, thrown ->
            thrown?.let {
                // Before it stood, the failure is the opener's to throw; after, there is no one left to tell but the
                // log.
                if (!opened.fail(it)) logError("the actors' scope failed as it closed: $it")
            }
        }
    val actors = opened.await().fold({ throw IllegalStateException("the actors' scope did not open", it) }, { it })
    return actors.also { it.holding = holding }
}

/** Spawns [behaviour] on [actors] and stops it when the scope ends, waiting until it has stopped. */
fun <M : Any> Wiring.spawn(actors: Actors, name: String, behaviour: Behaviour<M, *, *>): ActorRef<M> =
    install({ actors.spawn(name, behaviour) }) { ref, _ -> actors.stop(ref) }

/**
 * An actor as a node, keyed by the `ActorRef<M>` of its protocol: two actors are two keys wherever they answer to two
 * protocols, and a node depending on one names the protocol it sends. It has stopped before anything it depends on is
 * released.
 */
inline fun <reified M : Any> actor(name: String, noinline behaviour: () -> Behaviour<M, *, *>): Module =
    single { actors: Actors ->
        spawn(actors, name, behaviour())
    }

inline fun <reified M : Any, reified D1 : Any> actor(
    name: String,
    noinline behaviour: (D1) -> Behaviour<M, *, *>,
): Module = single { actors: Actors, d1: D1 -> spawn(actors, name, behaviour(d1)) }

inline fun <reified M : Any, reified D1 : Any, reified D2 : Any> actor(
    name: String,
    noinline behaviour: (D1, D2) -> Behaviour<M, *, *>,
): Module = single { actors: Actors, d1: D1, d2: D2 -> spawn(actors, name, behaviour(d1, d2)) }
