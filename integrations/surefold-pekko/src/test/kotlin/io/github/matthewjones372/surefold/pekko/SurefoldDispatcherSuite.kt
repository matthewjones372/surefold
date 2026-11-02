package io.github.matthewjones372.surefold.pekko

import com.typesafe.config.ConfigFactory
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.parZip
import dev.surefold.resource
import org.apache.pekko.ConfigurationException
import org.apache.pekko.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

private val DISPATCHERS = """
    pekko.actor.surefold {
        executor = "virtual-thread-executor"
    }
    pekko.actor.one-thread {
        type = PinnedDispatcher
        executor = "thread-pool-executor"
    }
    pekko.actor.pooled {
        executor = "fork-join-executor"
    }
""".trimIndent()

object SurefoldDispatcherSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = resource {
        install(
            { ActorSystem.create("surefold-dispatcher-test", ConfigFactory.parseString(DISPATCHERS)) },
        ) { system, _ ->
            system.terminate()
            Await.ready(system.whenTerminated(), FiniteDuration(10, TimeUnit.SECONDS))
        }
    }

    init {
        test(
            "a virtual-thread-executor dispatcher is accepted, and parZip's branches run on virtual threads",
        ) { system ->
            val on = system.surefoldDispatcher()

            val virtual = parZip(
                on = on,
                { Thread.currentThread().isVirtual },
                { Thread.currentThread().isVirtual },
            ) { first, second -> first to second }

            expect(virtual == (true to true)) {
                "Pekko's virtual-thread-executor hands each task a virtual thread, and a fork is a task"
            }
        }

        test("a PinnedDispatcher is accepted, and its one thread runs the branches one after another") { system ->
            val on = system.surefoldDispatcher("one-thread")

            val names = parZip(
                on = on,
                { Thread.currentThread().name },
                { Thread.currentThread().name },
            ) { first, second -> first to second }

            names.first shouldBe names.second
            names.first shouldContain "one-thread"
        }

        test("a fork-join-executor dispatcher is refused, and the message names the config key") { system ->
            shouldThrow<IllegalArgumentException>({ system.surefoldDispatcher("pooled") }) { refusal ->
                refusal.message shouldContain "pekko.actor.pooled.executor"
                refusal.message shouldContain "virtual-thread-executor"
                refusal.message shouldContain "PinnedDispatcher"
            }
        }

        test("the id defaults to surefold") { system ->
            system.surefoldDispatcher() shouldBeSameInstanceAs system.surefoldDispatcher("surefold")
        }

        test("an id nobody configured leaves as Pekko's own error") { system ->
            shouldThrow<ConfigurationException>({ system.surefoldDispatcher("absent") }) { unconfigured ->
                unconfigured.message shouldContain "pekko.actor.absent"
            }
        }
    }
}
