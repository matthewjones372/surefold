package dev.surefold.actor

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Scope
import dev.surefold.increment
import kotlin.time.Duration.Companion.milliseconds

/** What a topic's actor is told (spec 0082). */
sealed interface TopicMessage<M : Any> {
    /** [message] published here: to this node's subscribers, and on to the other nodes. */
    data class Publish<M : Any>(val message: M) : TopicMessage<M>

    /** [message] published on another node: to this node's subscribers only, and no further. */
    data class Arrive<M : Any>(val message: M) : TopicMessage<M>

    data class Subscribe<M : Any>(val subscriber: ActorRef<M>) : TopicMessage<M>

    data class Unsubscribe<M : Any>(val subscriber: ActorRef<M>) : TopicMessage<M>
}

/** A topic's own timer, to hand busy subscribers what it kept for them (spec 0095). */
internal class DrainTopic<M : Any> : TopicMessage<M>

/** The key of a topic's drain timer. */
private object TopicDrainKey

/**
 * A topic on this node: every message published to it reaches every subscriber, at most once, in the order one
 * publisher published them. A subscriber that stops is dropped; one that cannot be watched, such as a sharded entity or
 * a singleton, hears until it unsubscribes. A cluster's topic reaches the subscribers of every `Up` member (spec 0082).
 * A subscriber too busy to take a message has it kept for it, in order, and past what may be kept it is a dead letter
 * for being full; the others hear on (spec 0095).
 */
class Topic<M : Any> internal constructor(val name: String, val ref: ActorRef<TopicMessage<M>>) {
    /** [subscriber] hears every message published from now on, until it stops or [unsubscribe]. */
    fun subscribe(subscriber: ActorRef<M>) = ref.tell(TopicMessage.Subscribe(subscriber))

    fun unsubscribe(subscriber: ActorRef<M>) = ref.tell(TopicMessage.Unsubscribe(subscriber))

    /** Tells [message] to every subscriber, a publisher that subscribed included. */
    fun publish(message: M) = ref.tell(TopicMessage.Publish(message))
}

/**
 * The topic [name] on this scope, its actor at `/user/topic-<name>`. Each publish here is also handed to [forward],
 * which is how a cluster's topic reaches the other members; a message from them arrives as [TopicMessage.Arrive]. It
 * records `surefold.topic.published`, `surefold.topic.delivered` and `surefold.topic.subscribers` (spec 0081).
 */
fun <M : Any> Scope<*>.topic(name: String, forward: (M) -> Unit = {}): Topic<M> {
    val steps =
        TopicSteps<M>(
            published = counter("surefold.topic.published", "topic" to name),
            delivered = counter("surefold.topic.delivered", "topic" to name),
            subscribers = gauge("surefold.topic.subscribers", "topic" to name),
        )
    val actor =
        behaviour<TopicMessage<M>, Set<ActorRef<M>>>(emptySet()) { ctx, heard, message ->
                when (message) {
                    is TopicMessage.Publish ->
                        stay().also {
                            steps.published.increment()
                            steps.hear(ctx, heard, message.message)
                            forward(message.message)
                        }

                    is TopicMessage.Arrive -> stay().also { steps.hear(ctx, heard, message.message) }

                    is TopicMessage.Subscribe -> {
                        // One that cannot be watched stays until it unsubscribes.
                        if (message.subscriber.canBeWatched()) ctx.watch(message.subscriber)
                        become(steps.counted(heard + message.subscriber))
                    }

                    is TopicMessage.Unsubscribe -> become(steps.unsubscribed(ctx, heard, message.subscriber))

                    is DrainTopic -> stay().also { steps.drain(ctx) }
                }
            }
            .onSignal { ctx, heard, signal ->
                when (signal) {
                    is Signal.Terminated -> become(steps.counted(heard.filterTo(mutableSetOf()) { it != signal.ref }))
                    Signal.Stopping -> stay().also { steps.stopping(ctx) }
                }
            }
    return Topic(name, spawn("topic-$name", actor))
}

/** A topic's counts, and what it keeps for busy subscribers (spec 0095). */
@OptIn(PlumbingSeam::class)
private class TopicSteps<M : Any>(
    val published: Counter,
    private val delivered: Counter,
    private val subscribers: Gauge,
) {
    private val handOn = HandOn<ActorRef<M>, M>()

    fun counted(heard: Set<ActorRef<M>>) = heard.also { subscribers.set(it.size.toDouble()) }

    /** [message] to every subscriber that has room, and kept, in order, for those that have none. */
    fun hear(ctx: Ctx<TopicMessage<M>>, heard: Set<ActorRef<M>>, message: M) {
        var kept = false
        heard.forEach {
            if (handOn.tell(ctx, it, it, message)) kept = true
            delivered.increment()
        }
        if (kept) ctx.timers.after(TopicDrainKey, TOPIC_DRAIN_AFTER, DrainTopic())
    }

    /** [subscriber] gone, and what was kept for it with it. */
    fun unsubscribed(ctx: Ctx<TopicMessage<M>>, heard: Set<ActorRef<M>>, subscriber: ActorRef<M>): Set<ActorRef<M>> {
        handOn.forget(ctx, subscriber)
        return counted(heard - subscriber)
    }

    fun drain(ctx: Ctx<TopicMessage<M>>) {
        if (handOn.drain(ctx)) ctx.timers.after(TopicDrainKey, TOPIC_DRAIN_AFTER, DrainTopic())
    }

    fun stopping(ctx: Ctx<TopicMessage<M>>) = handOn.drop(ctx, DeadLetter.Why.Stopped)
}

/** How soon a topic offers a busy subscriber what it kept for it again. */
private val TOPIC_DRAIN_AFTER = 10.milliseconds
