package dev.surefold.cluster.saga

import dev.surefold.actor.AskFailure
import dev.surefold.actor.Ctx
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.stay
import dev.surefold.clock
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.sharding
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.metrics
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A durable saga run across a cluster: each saga is an entity of its own, sharded by its id (spec 0299). */
class Sagas<I, E, A> internal constructor(
    private val saga: Durable<I, E, A>,
    private val sharded: Sharded<SagaCommand>,
) {
    /** Starts the saga [id] with [input], answering once `Started` is journalled; one that has it runs nothing more. */
    fun start(id: String, input: I, within: Duration = 10.seconds): Either<AskFailure, Unit> =
        sharded.entity(id).ask(within) { SagaCommand.Start(saga.input.encode(input), it) }

    /** How the saga [id] stands, waiting up to [within] for it to end; [Durable.Outcome.Running] if it has not. */
    fun await(id: String, within: Duration): Durable.Outcome<E, A> =
        sharded.entity(id).ask<SagaCommand, Ending>(within + ANSWER_WITHIN) { SagaCommand.Await(within, it) }
            .fold({ Durable.Outcome.Running }, { it.outcome(saga) })
}

/**
 * This saga run on [cluster], as one sharded entity per id on whichever member owns it. Every node calls this with
 * the same saga. An entity stopped for [passivateAfter] while its body runs leaves the body running, and is started
 * again by the next message, which reads the journal anew.
 */
fun <I, E, A> Durable<I, E, A>.on(cluster: Cluster, passivateAfter: Duration = 10.minutes): Sagas<I, E, A> {
    val waker = cluster.waker()
    val sharded = cluster.sharding("saga-$kind", SagaCodec, passivateAfter) { id -> entity(id, waker) }
    waker.kind(kind) { id -> sharded.entity(id).tell(SagaCommand.Wake) }
    return Sagas(this, sharded)
}

/** How long past its own wait an `await` gives the answer to cross back. */
private val ANSWER_WITHIN = 2.seconds

/** How a saga stands, as it crosses between nodes: its answer or error still in their codecs' bytes. */
internal sealed interface Ending {
    class Done(val bytes: ByteArray) : Ending

    class Refused(val bytes: ByteArray) : Ending

    data object Running : Ending

    data class Stuck(val reason: String) : Ending

    data object Forgotten : Ending
}

private fun <I, E, A> Ending.outcome(saga: Durable<I, E, A>): Durable.Outcome<E, A> = when (this) {
    is Ending.Done -> Durable.Outcome.Done(saga.answer.decode(bytes))
    is Ending.Refused -> Durable.Outcome.Refused(saga.error.decode(bytes))
    Ending.Running -> Durable.Outcome.Running
    is Ending.Stuck -> Durable.Outcome.Stuck(reason)
    Ending.Forgotten -> Durable.Outcome.Forgotten
}

/** What a saga's entity handles: the first three from any node, the rest from its own runner and timers. */
internal sealed interface SagaCommand {
    class Start(val input: ByteArray, val reply: Reply<Unit>) : SagaCommand

    class Await(val within: Duration, val reply: Reply<Ending>) : SagaCommand

    /** Drive the saga if nothing here is: the `surefold-sagas` waker's word after a member is removed. */
    data object Wake : SagaCommand

    /** The runner found `Started` in the journal, or wrote it. */
    data object Begun : SagaCommand

    /** The runner stopped: how the saga ended, or null when another runner overtook it. */
    class Ran(val ending: Ending?) : SagaCommand

    class Expired(val reply: Reply<Ending>) : SagaCommand
}

/** Where a saga's entity is: nothing read yet, a runner driving it, or how that runner left it. */
private sealed interface Phase {
    data object Fresh : Phase

    data class Running(
        val started: Boolean,
        val input: ByteArray?,
        val starting: List<Reply<Unit>> = emptyList(),
        val awaiting: List<Reply<Ending>> = emptyList(),
    ) : Phase

    data class Ended(val ending: Ending) : Phase
}

/**
 * The entity of the saga [id]. Its turns never block: the body runs on a virtual thread of its own and tells the
 * entity how it went. A second runner, on a node the shard has just left, is stopped by the journal as [Overtaken].
 */
private fun <I, E, A> Durable<I, E, A>.entity(id: String, waker: Waker) =
    behaviour<SagaCommand, Phase>(Phase.Fresh) { ctx, phase, message ->
        val run = { input: ByteArray?, running: Phase.Running -> running.also { launch(ctx, id, input, waker) } }
        when (message) {
            is SagaCommand.Start -> starting(phase, message, run)

            is SagaCommand.Await -> when (phase) {
                is Phase.Ended -> stay().also { message.reply(phase.ending) }

                Phase.Fresh, is Phase.Running -> {
                    ctx.timers.after(message.reply, message.within, SagaCommand.Expired(message.reply))
                    val running = phase as? Phase.Running ?: run(null, Phase.Running(false, null))
                    become(running.copy(awaiting = running.awaiting + message.reply))
                }
            }

            SagaCommand.Wake -> if (phase == Phase.Fresh) become(run(null, Phase.Running(false, null))) else stay()

            SagaCommand.Begun, is SagaCommand.Ran, is SagaCommand.Expired ->
                (phase as? Phase.Running)?.let { become(heard(it, message, run)) } ?: stay()
        }
    }

private fun starting(phase: Phase, start: SagaCommand.Start, run: (ByteArray?, Phase.Running) -> Phase.Running) =
    when (phase) {
        is Phase.Running if phase.started -> stay().also { start.reply(Unit) }

        is Phase.Running ->
            become(phase.copy(input = phase.input ?: start.input, starting = phase.starting + start.reply))

        is Phase.Ended if phase.ending != Ending.Forgotten -> stay().also { start.reply(Unit) }

        Phase.Fresh, is Phase.Ended ->
            become(run(start.input, Phase.Running(false, start.input, listOf(start.reply))))
    }

/** What a running entity does with its runner's and its timers' word. */
private fun heard(phase: Phase.Running, message: SagaCommand, run: (ByteArray?, Phase.Running) -> Phase.Running) =
    when (message) {
        SagaCommand.Begun -> {
            phase.starting.forEach { it(Unit) }
            phase.copy(started = true, starting = emptyList())
        }

        is SagaCommand.Ran -> ran(message.ending, phase, run)

        is SagaCommand.Expired if message.reply in phase.awaiting -> {
            message.reply(Ending.Running)
            phase.copy(awaiting = phase.awaiting - message.reply)
        }

        is SagaCommand.Start, is SagaCommand.Await, SagaCommand.Wake, is SagaCommand.Expired -> phase
    }

/** Where a runner's end leaves the entity: a start that came while it found nothing is run now. */
private fun ran(ending: Ending?, phase: Phase.Running, run: (ByteArray?, Phase.Running) -> Phase.Running): Phase {
    if (ending == Ending.Forgotten && phase.input != null) return run(phase.input, phase.copy(started = false))
    // Overtaken: the other runner wrote `Started`, and the next message here reads what it wrote since.
    val answer = ending ?: Ending.Running
    phase.awaiting.forEach { it(answer) }
    phase.starting.forEach { it(Unit) }
    return if (ending == null) Phase.Fresh else Phase.Ended(ending)
}

/** Drives the saga [id] on a virtual thread, with the turn's clock and metrics, telling the entity how it went. */
private fun <I, E, A> Durable<I, E, A>.launch(ctx: Ctx<SagaCommand>, id: String, input: ByteArray?, waker: Waker) {
    val self = ctx.self
    val journal = ctx.journal
    val time = clock.get()
    val measured = metrics.get()
    Thread.ofVirtual().name("saga-$kind-$id").start {
        clock.locally(time) {
            metrics.locally(measured) {
                val ending = ending(journal, id, input, { waker.began(kind, id) }) { self.tell(SagaCommand.Begun) }
                if (ending != null && ending !is Ending.Stuck) waker.ended(kind, id)
                self.tell(SagaCommand.Ran(ending))
            }
        }
    }
}

private object EndingCodec : MessageCodec<Ending> {
    override fun write(message: Ending, out: WireOut) = when (message) {
        is Ending.Done -> {
            out.int(DONE)
            out.bytes(message.bytes)
        }

        is Ending.Refused -> {
            out.int(REFUSED)
            out.bytes(message.bytes)
        }

        Ending.Running -> out.int(RUNNING)

        is Ending.Stuck -> {
            out.int(STUCK)
            out.string(message.reason)
        }

        Ending.Forgotten -> out.int(FORGOTTEN)
    }

    override fun read(input: WireIn): Ending = when (val tag = input.int()) {
        DONE -> Ending.Done(input.bytes())
        REFUSED -> Ending.Refused(input.bytes())
        RUNNING -> Ending.Running
        STUCK -> Ending.Stuck(input.string())
        FORGOTTEN -> Ending.Forgotten
        else -> throw WireException("no saga ending has the tag $tag")
    }
}

/** A saga entity's messages on the wire: the input as its codec's bytes, and each reply as an address. */
internal object SagaCodec : MessageCodec<SagaCommand> {
    override fun write(message: SagaCommand, out: WireOut) = when (message) {
        is SagaCommand.Start -> {
            out.int(START)
            out.bytes(message.input)
            out.reply(message.reply, Codecs.unit)
        }

        is SagaCommand.Await -> {
            out.int(AWAIT)
            out.long(message.within.inWholeMilliseconds)
            out.reply(message.reply, EndingCodec)
        }

        SagaCommand.Wake -> out.int(WAKE)

        SagaCommand.Begun, is SagaCommand.Ran, is SagaCommand.Expired -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): SagaCommand = when (val tag = input.int()) {
        START -> SagaCommand.Start(input.bytes(), input.reply(Codecs.unit))
        AWAIT -> SagaCommand.Await(input.long().milliseconds, input.reply(EndingCodec))
        WAKE -> SagaCommand.Wake
        else -> throw WireException("no saga message has the tag $tag")
    }
}

private const val START = 1
private const val AWAIT = 2
private const val WAKE = 3
private const val DONE = 1
private const val REFUSED = 2
private const val RUNNING = 3
private const val STUCK = 4
private const val FORGOTTEN = 5
