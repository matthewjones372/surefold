package dev.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.Reply
import dev.surefold.actor.behaviour
import dev.surefold.actor.stay
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.javadsl.Behaviors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.apache.pekko.actor.typed.ActorRef as PekkoRef

// Each actor is written twice, once per runtime, doing the same work: a benchmark compares runtimes, not code.

/** A message that counts itself done: the latch is how an invocation knows every message was handled. */
class Hit(val done: CountDownLatch)

/** Blocks its step for [BLOCK_MILLIS] before counting itself done, as a repository call would. */
class Slow(val done: CountDownLatch)

class SurefoldBall(val left: Int, val back: ActorRef<SurefoldBall>, val done: CountDownLatch)

class PekkoBall(val left: Int, val back: PekkoRef<PekkoBall>, val done: CountDownLatch)

/** A question whose answer is the number it carries. */
class SurefoldQuestion(val n: Int, val reply: Reply<Int>)

class PekkoQuestion(val n: Int, val reply: PekkoRef<Int>)

internal const val BLOCK_MILLIS = 1L

fun surefoldCounter(): Behaviour<Hit, Unit, Nothing> = behaviour(Unit) { _, _, hit ->
    hit.done.countDown()
    stay()
}

fun pekkoCounter(): Behavior<Hit> = Behaviors.receiveMessage { hit ->
    hit.done.countDown()
    Behaviors.same()
}

fun surefoldAnswerer(): Behaviour<SurefoldQuestion, Unit, Nothing> = behaviour(Unit) { _, _, question ->
    question.reply(question.n)
    stay()
}

fun pekkoAnswerer(): Behavior<PekkoQuestion> = Behaviors.receiveMessage { question ->
    question.reply.tell(question.n)
    Behaviors.same()
}

fun surefoldPaddle(): Behaviour<SurefoldBall, Unit, Nothing> = behaviour(Unit) { ctx, _, ball ->
    if (ball.left == 0) ball.done.countDown() else ball.back.tell(SurefoldBall(ball.left - 1, ctx.self, ball.done))
    stay()
}

fun pekkoPaddle(): Behavior<PekkoBall> = Behaviors.setup { ctx ->
    Behaviors.receiveMessage { ball ->
        if (ball.left == 0) ball.done.countDown() else ball.back.tell(PekkoBall(ball.left - 1, ctx.self, ball.done))
        Behaviors.same()
    }
}

fun surefoldSlow(): Behaviour<Slow, Unit, Nothing> = behaviour(Unit) { _, _, slow ->
    block()
    slow.done.countDown()
    stay()
}

fun pekkoSlow(): Behavior<Slow> = Behaviors.receiveMessage { slow ->
    block()
    slow.done.countDown()
    Behaviors.same()
}

/** A millisecond of blocking. Parking rather than sleeping, which the build forbids outside tests, costs the same. */
private fun block() = LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(BLOCK_MILLIS))
