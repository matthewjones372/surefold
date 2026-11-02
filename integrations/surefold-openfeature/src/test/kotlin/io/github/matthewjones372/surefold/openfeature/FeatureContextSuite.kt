package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.surefold.Resource
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.parZip
import dev.surefold.resource
import dev.surefold.scope
import kotlin.time.Duration.Companion.minutes

private data class Who(val reply: Reply<Pair<String, Int>>)

/** Spec 0180: the context bound where a request came in is the one every flag read below it answers for. */
object FeatureContextSuite : SharedSuite<Client>() {
    override val shared: Resource<Client> = resource {
        OpenFeatureAPI.getInstance().run {
            useSurefold()
            setProviderAndWait(Echo)
            client
        }
    }

    init {
        test("a flag read inside a parZip branch answers for the key evaluatedFor bound") { client ->
            val (left, right) = evaluatedFor("ada") {
                parZip({ client.getStringValue("who", "nobody") }, { client.getStringValue("who", "nobody") }) { a, b ->
                    a to b
                }
            }

            withClue("the SDK's own ThreadLocal propagator would leave both branches answering for nobody") {
                left shouldBe "ada"
                right shouldBe "ada"
            }
        }

        test("a flag read outside evaluatedFor answers for nobody") { client ->
            evaluatedFor("ada") { client.getStringValue("who", "nobody") } shouldBe "ada"
            client.getStringValue("who", "nobody") shouldBe "nobody"
        }

        test("a nested evaluatedFor adds to the context around it rather than replacing it") { client ->
            val (who, limit) = evaluatedFor("ada", "limit" to 3) {
                evaluatedFor("ada", "tier" to "gold") {
                    client.getStringValue("who", "nobody") to client.getIntegerValue("limit", 0)
                }
            }

            who shouldBe "ada"
            withClue("the inner call's attributes merge over the outer's, so the outer limit stays") {
                limit shouldBe 3
            }
        }

        test("an actor handler answers for the key and attributes the message was sent under") { client ->
            val handled = scope {
                val actor = spawn(
                    "reader",
                    behaviour<Who, Unit>(Unit) { _, _, message ->
                        stay().also {
                            message.reply(client.getStringValue("who", "nobody") to client.getIntegerValue("limit", 0))
                        }
                    },
                )
                evaluatedFor("ada", "limit" to 3) { actor.ask(1.minutes) { Who(it) }.getOrNull()!! }
            }

            withClue("an actor spawned outside the block sees the context only if it rides the message") {
                handled shouldBe ("ada" to 3)
            }
        }

        test("an attribute a Value cannot hold is refused, naming the attribute") { _ ->
            val since = "since" to Thread.currentThread()
            shouldThrow<IllegalArgumentException>({ evaluatedFor("ada", since) { } }) { refused ->
                refused.message shouldContain "since"
            }
        }

        test("the propagator reads what was set through it") { _ ->
            val propagator = SurefoldTransactionContextPropagator()
            val before = propagator.transactionContext

            propagator.transactionContext = ImmutableContext("grace")

            propagator.transactionContext.targetingKey shouldBe "grace"
            featureContext.get().targetingKey shouldBe "grace"
            propagator.transactionContext = before
        }
    }
}
