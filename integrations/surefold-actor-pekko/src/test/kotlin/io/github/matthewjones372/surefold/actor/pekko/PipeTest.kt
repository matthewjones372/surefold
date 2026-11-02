package io.github.matthewjones372.surefold.actor.pekko

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.ChildFailed
import org.apache.pekko.actor.typed.Terminated
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private sealed interface Work

private data class Run(val body: () -> Boolean, val seen: CompletableFuture<Either<String, Boolean>>) : Work

private data class Refuse(val seen: CompletableFuture<Either<String, Boolean>>) : Work

private data class Staged(
    val stage: CompletableFuture<Either<String, Boolean>>,
    val seen: CompletableFuture<Either<String, Boolean>>,
) : Work

private data object Boom : Work

private data class Done(
    val outcome: Either<String, Boolean>,
    val seen: CompletableFuture<Either<String, Boolean>>,
) : Work

private fun worker(): Behavior<Work> = receive { context, message ->
    when (message) {
        is Run -> context.pipeToSelf({ Done(it, message.seen) }) { message.body() }
        is Refuse -> context.pipeToSelf({ Done(it, message.seen) }) { fail("refused") }
        is Staged -> context.pipeToSelf(message.stage) { Done(it, message.seen) }
        Boom -> context.pipeToSelf({ Done(it, CompletableFuture()) }) { error("a defect, not a reply") }
        is Done -> same<Work>().also { message.seen.complete(message.outcome) }
    }
}

/** Spec 0058: `pipeToSelf` in surefold's error model. */
class PipeTest {

    companion object {
        @JvmStatic
        val system: ActorSystem = ActorSystem.create("surefold-actor-pekko-pipe-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    private fun <A> CompletableFuture<A>.answer(): A = get(5, TimeUnit.SECONDS)

    @Test
    fun `the block runs on a virtual thread, and its value arrives as a Right message`() {
        val ref = Adapter.spawn(system, worker(), "virtual")
        val seen = CompletableFuture<Either<String, Boolean>>()

        ref.tell(Run({ Thread.currentThread().isVirtual }, seen))

        seen.answer() shouldBe true.right()
    }

    @Test
    fun `a fail in the block arrives as a Left message`() {
        val ref = Adapter.spawn(system, worker(), "refusing")
        val seen = CompletableFuture<Either<String, Boolean>>()

        ref.tell(Refuse(seen))

        seen.answer() shouldBe "refused".left()
    }

    @Test
    fun `a stage that completes with an Either is wrapped as it comes`() {
        val ref = Adapter.spawn(system, worker(), "staged")
        val stage = CompletableFuture<Either<String, Boolean>>()
        val seen = CompletableFuture<Either<String, Boolean>>()

        ref.tell(Staged(stage, seen))
        stage.complete("late".left())

        seen.answer() shouldBe "late".left()
    }

    @Test
    fun `a throw in the block fails the actor rather than arriving as a message`() {
        val ended = CompletableFuture<Terminated>()
        val watching: Behavior<Any> = Behaviors.setup { context ->
            val child = context.spawn(worker(), "throwing")
            context.watch(child)
            child.tell(Boom)
            Behaviors.receive(Any::class.java)
                .onSignal(Terminated::class.java) { signal ->
                    ended.complete(signal)
                    Behaviors.same()
                }
                .build()
        }

        Adapter.spawn(system, watching, "watching")

        val signal = ended.answer()
        signal.shouldBeInstanceOf<ChildFailed>()
        signal.cause.message shouldBe "a defect, not a reply"
    }
}
