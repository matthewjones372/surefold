package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.fail.bind
import dev.surefold.fail.ensure
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.parZip
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The scope an element body runs in is a `Raise`, so everything a surefold handler
 * writes — `bind`, `ensure`, a fork of its own — is in reach of an operator.
 */
object RaiseInStreamSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-raise-test")

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun lookup(id: Int): Either<NoCustomer, String> =
        if (id == 2) NoCustomer(id).left() else "customer-$id".right()

    init {
        test("bind on a Left inside mapOrFail fails the stream with what the Left holds") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> lookup(id).bind() }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(2))
        }

        test("bind on a Right carries its value on as the element") { pekko ->
            val exit = Stream.from(listOf(1, 3))
                .mapOrFail { id -> lookup(id).bind() }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("customer-1", "customer-3"))
        }

        test("ensure fails the stream with the error it names") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id ->
                    ensure(id != 2) { NoCustomer(id) }
                    "customer-$id"
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(2))
        }

        test("parZip inside mapOrFail runs its branches on forks and combines what they answer") { pekko ->
            val virtual = ConcurrentLinkedQueue<Boolean>()

            val exit = Stream.from(listOf(1, 3))
                .mapOrFail { id ->
                    parZip(
                        {
                            virtual.add(Thread.currentThread().isVirtual)
                            lookup(id).bind()
                        },
                        {
                            virtual.add(Thread.currentThread().isVirtual)
                            id * 10
                        },
                    ) { name, total -> "$name:$total" }
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("customer-1:10", "customer-3:30"))
            withClue("every branch of a parZip in an element body runs on a fork of its own") {
                virtual.toList() shouldBe List(4) { true }
            }
        }

        test("a raise in a parZip branch fails the stream with that error") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id ->
                    parZip({ lookup(id).bind() }, { id * 10 }) { name, total -> "$name:$total" }
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(2))
        }

        test("raise and fail are one call under two names") { pekko ->
            val raised = Stream.from(listOf(1))
                .mapOrFail { id -> fail(NoCustomer(id)) }
                .runCollect()
                .run(pekko.system)
                .settled()

            val failed = Stream.from(listOf(1))
                .mapOrFail { id -> fail(NoCustomer(id)) }
                .runCollect()
                .run(pekko.system)
                .settled()

            raised shouldBe Exit.Failed(NoCustomer(1))
            withClue("fail is the name dipper gave raise, and must end a stream the same way") {
                failed shouldBe raised
            }
        }
    }
}
