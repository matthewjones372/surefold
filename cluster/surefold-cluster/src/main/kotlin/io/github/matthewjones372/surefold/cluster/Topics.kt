package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.Address
import dev.surefold.actor.Topic
import dev.surefold.actor.TopicMessage
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.topic

/**
 * The topic [name] across the cluster (spec 0082): a publish on any member reaches the subscribers of every member,
 * at most once each, in the order one member published. Each publish here is sent once to the topic of every other
 * `Up` member, whether or not it has subscribers, and goes no further from there. Every node calls this with the
 * same [name] and [codec].
 */
fun <M : Any> Cluster.topic(name: String, codec: MessageCodec<M>): Topic<M> {
    val wire = ArriveCodec(codec)
    val topic = scope.topic<M>(name) { message ->
        view.members.filter { it.status == Status.Up && it.node != self }.forEach { member ->
            remote.remote(Address(member.node.toString(), "/user/topic-$name", 0), wire)
                .tell(TopicMessage.Arrive(message))
        }
    }
    remote.expose(topic.ref, wire)
    return topic
}

/** A topic's message between nodes: only ever one published elsewhere, heard where it arrives. */
private class ArriveCodec<M : Any>(private val codec: MessageCodec<M>) : MessageCodec<TopicMessage<M>> {
    override fun write(message: TopicMessage<M>, out: WireOut) {
        require(message is TopicMessage.Arrive) { "only a published message crosses to another node, not $message" }
        codec.write(message.message, out)
    }

    override fun read(input: WireIn): TopicMessage<M> = TopicMessage.Arrive(codec.read(input))
}
