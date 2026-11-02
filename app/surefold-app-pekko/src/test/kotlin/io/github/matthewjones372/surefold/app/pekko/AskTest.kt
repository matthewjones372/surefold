package io.github.matthewjones372.surefold.app.pekko

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import io.github.matthewjones372.surefold.actor.pekko.answer
import io.github.matthewjones372.surefold.actor.pekko.ask
import io.github.matthewjones372.surefold.actor.pekko.receive
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Optional
import kotlin.time.Duration.Companion.seconds

private data class Kept(val name: String)

private sealed interface Ledger

private data class Look(val name: String, val replyTo: ActorRef<Optional<Kept>>) : Ledger

// Spec 0058: the ledger in surefold-actor-pekko's own words, where it was Pekko's Java DSL and class tokens.
private fun ledger(kept: Map<String, Kept>): Behavior<Ledger> = receive { message ->
    when (message) {
        is Look -> message.replyTo.answer(Optional.ofNullable(kept[message.name]))
    }
}

/** What an actor may answer with, and what it may not. */
class AskTest {

    companion object {
        @JvmStatic
        val system: ActorSystem = ActorSystem.create("surefold-app-pekko-ask-test")

        @JvmStatic
        @AfterAll
        fun stop() {
            system.terminate()
        }
    }

    @TempDir
    lateinit var workspace: File

    @Test
    fun `an answer comes back on the calling thread`() {
        val kept = mapOf("anvil" to Kept("anvil"))
        val ref = org.apache.pekko.actor.typed.javadsl.Adapter.spawn(system, ledger(kept), "ledger")

        val found: Optional<Kept> = ref.ask(system, 3.seconds) { replyTo -> Look("anvil", replyTo) }
        val missing: Optional<Kept> = ref.ask(system, 3.seconds) { replyTo -> Look("nothing", replyTo) }

        found shouldBe Optional.ofNullable(Kept("anvil"))
        withClue("absence is a value, because it cannot be a null message") {
            missing shouldBe Optional.ofNullable(null)
        }
    }

    @Test
    fun `a nullable reply does not compile`() {
        val fixture = """
            import io.github.matthewjones372.surefold.actor.pekko.answer
import io.github.matthewjones372.surefold.actor.pekko.ask
import io.github.matthewjones372.surefold.actor.pekko.receive
            import org.apache.pekko.actor.ActorSystem
            import org.apache.pekko.actor.typed.ActorRef
            import kotlin.time.Duration.Companion.seconds

            data class Kept(val name: String)
            data class Look(val name: String, val replyTo: ActorRef<Kept?>)

            fun broken(ref: ActorRef<Look>, system: ActorSystem): Kept? =
                ref.ask(system, 3.seconds) { replyTo -> Look("anvil", replyTo) }
        """.trimIndent()

        val (exit, errors) = EmbeddedKotlin(workspace).compile(fixture)

        withClue("Pekko refuses a null message, so the reply type refuses a nullable one") {
            exit shouldBe ExitCode.COMPILATION_ERROR
        }
        withClue("Reply is bound to Any, so it is inferred as Kept and the nullable ref no longer fits") {
            errors.joinToString("\n") shouldContain "ActorRef<Kept?>"
        }
    }

    @Test
    fun `the ask fixture is the only thing stopping it`() {
        val working = """
            import java.util.Optional
            import io.github.matthewjones372.surefold.actor.pekko.answer
import io.github.matthewjones372.surefold.actor.pekko.ask
import io.github.matthewjones372.surefold.actor.pekko.receive
            import org.apache.pekko.actor.ActorSystem
            import org.apache.pekko.actor.typed.ActorRef
            import kotlin.time.Duration.Companion.seconds

            data class Kept(val name: String)
            data class Look(val name: String, val replyTo: ActorRef<Optional<Kept>>)

            fun works(ref: ActorRef<Look>, system: ActorSystem): Optional<Kept> =
                ref.ask(system, 3.seconds) { replyTo -> Look("anvil", replyTo) }
        """.trimIndent()

        val (_, errors) = EmbeddedKotlin(workspace).compile(working)

        errors.shouldBeEmpty()
    }

    @Test
    fun `a sealed protocol a receive leaves a message out of does not compile`() {
        val fixture = """
            import io.github.matthewjones372.surefold.actor.pekko.receive
            import io.github.matthewjones372.surefold.actor.pekko.same
            import org.apache.pekko.actor.typed.Behavior

            sealed interface Shop
            data class Arrived(val pet: String) : Shop
            data class Left(val pet: String) : Shop

            fun shop(): Behavior<Shop> = receive { message ->
                when (message) {
                    is Arrived -> same()
                }
            }
        """.trimIndent()

        val (exit, errors) = EmbeddedKotlin(workspace).compile(fixture)

        exit shouldBe ExitCode.COMPILATION_ERROR
        withClue("the when is the body, so a message it leaves out is a missing branch") {
            errors.joinToString("\n") shouldContain "exhaustive"
        }
    }

    @Test
    fun `a nullable answer does not compile`() {
        val fixture = """
            import io.github.matthewjones372.surefold.actor.pekko.answer
            import io.github.matthewjones372.surefold.actor.pekko.receive
            import org.apache.pekko.actor.typed.ActorRef
            import org.apache.pekko.actor.typed.Behavior

            data class Kept(val name: String)
            data class Look(val name: String, val replyTo: ActorRef<Kept?>)

            fun ledger(): Behavior<Look> = receive { message -> message.replyTo.answer(null) }
        """.trimIndent()

        val (exit, errors) = EmbeddedKotlin(workspace).compile(fixture)

        withClue("answer is bound to Any, as ask's reply is: Pekko refuses a null message") {
            exit shouldBe ExitCode.COMPILATION_ERROR
        }
        withClue("an ActorRef<Kept?> is not a receiver answer applies to") {
            errors.joinToString("\n") shouldContain "receiver type mismatch"
        }
    }

    @Test
    fun `an actor in receive, same, stopped and answer compiles with its context`() {
        val working = """
            import io.github.matthewjones372.surefold.actor.pekko.answer
            import io.github.matthewjones372.surefold.actor.pekko.receive
            import io.github.matthewjones372.surefold.actor.pekko.same
            import io.github.matthewjones372.surefold.actor.pekko.stopped
            import org.apache.pekko.actor.typed.ActorRef
            import org.apache.pekko.actor.typed.Behavior

            sealed interface Shop
            data class Arrived(val pet: String) : Shop
            data class Count(val replyTo: ActorRef<Int>) : Shop
            data object Close : Shop

            fun shop(pets: List<String>): Behavior<Shop> = receive { context, message ->
                when (message) {
                    is Arrived -> shop(pets + message.pet).also { context.log.info("arrived") }
                    is Count -> message.replyTo.answer(pets.size)
                    Close -> stopped()
                }
            }

            fun idle(): Behavior<Shop> = receive { _ -> same() }
        """.trimIndent()

        val (_, errors) = EmbeddedKotlin(workspace).compile(working)

        errors.shouldBeEmpty()
    }
}
