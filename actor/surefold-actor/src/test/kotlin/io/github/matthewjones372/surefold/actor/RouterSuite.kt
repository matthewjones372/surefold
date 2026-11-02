package io.github.matthewjones372.surefold.actor

import dev.surefold.Schedule
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

private sealed interface Errand

private data class Fetch(val pet: String) : Errand

private data object Topple : Errand

private data object Resign : Errand

/** Keeps the runner in its step until [open] opens, saying when it began: a routee busy with a full mailbox. */
private data class Stall(val began: CountDownLatch, val open: CountDownLatch) : Errand

/** Asks which runner took it. */
private data class Whose(val reply: Reply<String>) : Errand

/** Writes down, under its own path, every pet it fetched. */
private fun runner(served: ConcurrentHashMap<String, List<String>>) =
    behaviour<Errand, Unit>(Unit) { ctx, _, message ->
        when (message) {
            is Fetch -> {
                served.merge(ctx.self.address.path, listOf(message.pet)) { was, now -> was + now }
                stay()
            }

            Topple -> error("the runner toppled")

            Resign -> stop()

            is Stall -> {
                message.began.countDown()
                message.open.await()
                stay()
            }

            is Whose -> {
                message.reply(ctx.self.address.path)
                stay()
            }
        }
    }

private fun TestActor<*, *, *>.routees(): List<TestActor<*, *, *>> = children.map { it as TestActor<*, *, *> }

object RouterSuite : Suite() {
    init {
        // Each test keeps its own record of what was served: in Jupiter this was a field, fresh per test.
        test("a pool of four hands eight messages two to each") {
            val served = ConcurrentHashMap<String, List<String>>()
            val pool = pool(4) { runner(served) }.test("pool")

            repeat(8) { pool.send(Fetch("pet-$it")) }

            served.values.map { it.size } shouldContainExactly listOf(2, 2, 2, 2)
            served.keys shouldBe (1..4).map { "/user/pool/routee-$it" }.toSet()
        }

        test("hashing sends one key to one routee, always") {
            val served = ConcurrentHashMap<String, List<String>>()
            val byPet = hashing { errand: Errand -> (errand as? Fetch)?.pet }
            val pool = pool(4, route = byPet) { runner(served) }.test("pool")

            listOf("sam", "bo", "sam", "kit", "bo", "sam").forEach { pool.send(Fetch(it)) }

            listOf("sam", "bo", "kit").forEach { pet -> served.values.count { pet in it } shouldBe 1 }
            served.values.flatten() shouldContainExactlyInAnyOrder listOf("sam", "bo", "sam", "kit", "bo", "sam")
        }

        test("a failed routee restarts alone") {
            val served = ConcurrentHashMap<String, List<String>>()
            val pool = pool(4, restart = Schedule.recurs(1)) { runner(served) }.test("pool")

            pool.send(Topple)
            repeat(4) { pool.send(Fetch("pet-$it")) }

            pool.routees().map { it.restarts } shouldContainExactly listOf(1, 0, 0, 0)
            served.values.map { it.size } shouldContainExactly listOf(1, 1, 1, 1)
        }

        test("a routee that stops leaves the pool smaller, and a pool with none left stops") {
            val served = ConcurrentHashMap<String, List<String>>()
            val pool = pool(2) { runner(served) }.test("pool")

            pool.send(Resign)
            pool.send(Fetch("sam"))
            pool.send(Fetch("bo"))
            pool.send(Resign)

            withClue("the first routee resigned, so the second took everything after") {
                served shouldBe mapOf("/user/pool/routee-2" to listOf("sam", "bo"))
            }
            pool.stopped shouldBe true
        }

        test("a group routes over refs already running, with no actor between") {
            val served = ConcurrentHashMap<String, List<String>>()
            testActors {
                val runners = (1..3).map { spawn("runner-$it", runner(served)) }
                val group = group(runners)

                repeat(6) { group.tell(Fetch("pet-$it")) }

                served.values.map { it.size } shouldContainExactly listOf(2, 2, 2)
            }
        }

        test("on threads, a pool of four hands eight messages two to each") {
            val served = ConcurrentHashMap<String, List<String>>()
            scope {
                val pool = spawn("pool", pool(4) { runner(served) })
                repeat(8) { pool.tell(Fetch("pet-$it")) }
                awaitIdle()
            }

            served.values.map { it.size } shouldContainExactly listOf(2, 2, 2, 2)
        }

        test("on threads, a pool passes over a routee whose mailbox is full, for the next") {
            val served = ConcurrentHashMap<String, List<String>>()
            val began = CountDownLatch(1)
            val open = CountDownLatch(1)
            val answers = scope {
                // Each routee has room for one message: routee-1, busy in its step, fills with one more.
                val pool = spawn("pool", pool(2) { runner(served) }, capacity = 1)
                pool.tell(Stall(began, open))
                began.await()
                // Round robin: routee-2, then routee-1, whose mailbox this fills, then routee-2, then routee-1 again.
                val turns = listOf(
                    pool.ask(1.minutes) { Whose(it) },
                    pool.tell(Fetch("fills routee-1")),
                    pool.ask(1.minutes) { Whose(it) },
                )
                val passedOver = pool.ask(1.minutes) { Whose(it) }
                open.countDown()
                awaitIdle()
                turns + passedOver
            }

            val routee2 = "/user/pool/routee-2".right()
            answers shouldBe listOf(routee2, Unit, routee2, routee2)
            served["/user/pool/routee-1"] shouldBe listOf("fills routee-1")
        }
    }
}
