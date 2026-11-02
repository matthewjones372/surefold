package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import java.util.concurrent.CompletionStage

/** The builders that start from values already in hand, asserted on a real system. */
object BuildersSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-builders-test")

    private data class Customer(val id: Int, val name: String)

    private data class Missing(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** The directory the shape's lookup reads: id 1 is there and nothing else is. */
    private fun lookup(id: Int): Customer? = if (id == 1) Customer(1, "ada") else null

    init {
        test("single carries the one element it was given") { pekko ->
            val exit = Stream.single(Customer(1, "ada")).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf(Customer(1, "ada")))
        }

        test("a stream from single is a stream like any other") { pekko ->
            val exit = Stream.single(Customer(2, "grace"))
                .map { customer -> customer.name.uppercase() }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf("GRACE"))
        }

        test("of carries the elements it names in the order it names them") { pekko ->
            val exit = Stream.of("ada", "grace", "alan").runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf("ada", "grace", "alan"))
        }

        test("of with no arguments ends the way the empty stream does") { pekko ->
            val nothingNamed = Stream.of<String>().runCollect().run(pekko.system).settled()
            val empty = Stream.empty().runCollect().run(pekko.system).settled()

            withClue("naming no elements is the empty stream spelled another way") {
                nothingNamed shouldBe empty
            }
        }

        test("a lookup that finds its value is a stream of the one it found") { pekko ->
            val id = 1

            val found = lookup(id)?.let { customer -> Stream.single(customer) } ?: Stream.fail(Missing(id))

            found.runCollect().run(pekko.system).settled() shouldBe Exit.Done(listOf(Customer(1, "ada")))
        }

        // The choice the element bound forces: absence is a failure with a name, at the lookup.
        test("a lookup that misses fails with the error it names rather than running empty") { pekko ->
            val id = 7

            val found = lookup(id)?.let { customer -> Stream.single(customer) } ?: Stream.fail(Missing(id))

            found.runCollect().run(pekko.system).settled() shouldBe Exit.Failed(Missing(7))
        }
    }
}
