package dev.surefold.cluster.saga

import dev.surefold.Schedule
import dev.surefold.Settled
import dev.surefold.actor.ActorRef
import dev.surefold.actor.AskFailure
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.StateCodec
import dev.surefold.actor.ask
import dev.surefold.clock
import dev.surefold.cluster.Sharded
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.bind
import dev.surefold.fail.fold
import dev.surefold.retrying
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * A resumed saga whose [step] was sent longer ago than its entity remembers a key: asking what the key got could answer
 * `Unknown` for a command that was applied, so nothing is asked or sent again.
 */
class Outlived(val saga: String, val step: String) :
    IllegalStateException("saga $saga was stopped longer than $step's entity remembers its key")

/**
 * A step that has the keyed entity [id] of [sharded] apply [command] under the step's key, and waits for its answer. A
 * refusal fails the saga with [refused] of its reason; an answer lost on the way back is asked for with
 * `Keyed.Applied`, and the command sent again only if the entity never had it. A step begun when its node died is
 * settled the same way, so one on reachable entities always settles. [undo] is applied under the key's `/undo`, and a
 * refused undo leaves the saga [Stuck]. [remember] is the entity kind's own window: past it, resuming the step throws
 * [Outlived]. Each ask waits [within] for its answer.
 */
fun <E, C : Any, R : Any> DurableScope<E>.entityStep(
    name: String,
    sharded: Sharded<Keyed<C, R>>,
    id: String,
    refused: (R) -> E,
    undo: (() -> C)? = null,
    remember: Duration = 30.days,
    within: Duration = 5.seconds,
    command: () -> C,
) {
    val entity = sharded.entity(id)
    // Journalled, so a resumed run knows when the command was first sent.
    val sent = now()
    val remembered = { if (clock.get().now() > sent + remember) throw Outlived(this.id, name) }
    fun answered(answer: KeyAnswer<R>): Unit =
        when (answer) {
            KeyAnswer.Accepted -> Unit
            is KeyAnswer.Refused -> fail(refused(answer.reason))
            KeyAnswer.Unknown -> error("$name's entity answered an apply with Unknown")
        }
    step(
        name,
        NoValue,
        action = { key -> answered(entity.applied(key, command(), within)) },
        check = { key ->
            remembered()
            when (val answer = entity.asked(key, within)) {
                KeyAnswer.Unknown -> Settled.DidNot
                KeyAnswer.Accepted,
                is KeyAnswer.Refused -> Settled.Happened(answered(answer))
            }
        },
        undo =
            undo?.let { undoing ->
                { key, _, _ ->
                    remembered()
                    val answer = entity.applied(key, undoing(), within)
                    check(answer == KeyAnswer.Accepted) { "$name's entity answered its undo $answer" }
                }
            },
    )
}

private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())

/** How long after an ask that went unanswered the next is made. */
private val ASK_AGAIN_AFTER = 100.milliseconds

/**
 * What the entity answers [command] under [key]. An answer that does not come is asked for, and the command sent again
 * only once the entity says it never had the key.
 */
private fun <C : Any, R : Any> ActorRef<Keyed<C, R>>.applied(key: String, command: C, within: Duration): KeyAnswer<R> {
    fun apply() = ask<Keyed<C, R>, KeyAnswer<R>>(within) { Keyed.Apply(key, command, it) }
    return apply().fold(
        {
            answering { asked(key, within).takeUnless { it == KeyAnswer.Unknown } ?: apply().bind() }
        },
    ) {
        it
    }
}

/** What the entity says [key] got, asked until it answers. */
private fun <C : Any, R : Any> ActorRef<Keyed<C, R>>.asked(key: String, within: Duration): KeyAnswer<R> = answering {
    ask<Keyed<C, R>, KeyAnswer<R>>(within) { Keyed.Applied(key, it) }.bind()
}

/** [ask] again every [ASK_AGAIN_AFTER] until it answers: a step on our own entities waits for them to be reachable. */
private fun <A> answering(ask: Fail<AskFailure>.() -> A): A =
    when (val answered = Schedule.spaced<AskFailure>(ASK_AGAIN_AFTER).retrying(ask)) {
        is Either.Right -> answered.value

        // A spaced schedule never ends, so the retry never gives up.
        is Either.Left -> error("gave up asking: ${answered.value}")
    }

/** An entity step's journalled value: nothing, since its answer is the entity's to keep. */
private object NoValue : StateCodec<Unit> {
    override fun encode(state: Unit): ByteArray = ByteArray(0)

    override fun decode(bytes: ByteArray) = Unit
}
