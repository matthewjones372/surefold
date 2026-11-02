package io.github.matthewjones372.surefold.bank

import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.stay
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration

/** What the admin page's load button tells a node's generator. */
internal sealed interface Load {
    data object On : Load

    data object Off : Load

    data object Tick : Load
}

/** The accounts a load moves money between; many, so no one account is sent a burst. */
private val loaded = List(50) { "load-${it.toString().padStart(2, '0')}" }

private const val OPENING = 1_000_000L
private const val MOST = 500L

/**
 * Random transfers between [loaded] accounts, one every [every], sent through [node] while on. Paced by a timer
 * rather than sent as fast as they go: a burst of more than a mailbox holds to one entity stops its region (spec 0095).
 */
internal fun load(node: BankNode, every: Duration) = behaviour<Load, Boolean>(false) { ctx, on, message ->
    when (message) {
        Load.On -> {
            if (!on) {
                loaded.forEach { node.open(it, OPENING) }
                ctx.timers.every(Load.Tick, every, Load.Tick)
            }
            become(true)
        }

        Load.Off -> {
            ctx.timers.cancel(Load.Tick)
            become(false)
        }

        Load.Tick -> {
            val (from, to) = loaded.shuffled().take(2)
            node.transfer("t-${UUID.randomUUID()}", from, to, Random.nextLong(1, MOST))
            stay()
        }
    }
}
