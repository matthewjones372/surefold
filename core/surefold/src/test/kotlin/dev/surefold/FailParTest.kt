package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.left
import dev.surefold.fail.right
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

private data class User(val name: String)

private data class Order(val id: Long)

private data class Dashboard(val user: User, val orders: List<Order>)

private fun findUser(id: Long): Either<Bad, User> = if (id == 1L) User("Ada").right() else Bad("no user $id").left()

private fun ordersFor(id: Long): Either<Bad, List<Order>> = listOf(Order(id)).right()

class FailParTest {

    private fun dashboard(id: Long): Either<Bad, Dashboard> = attempt {
        parZip({ findUser(id).bind() }, { ordersFor(id).bind() }) { u, o -> Dashboard(u, o) }
    }

    @Test
    fun `parZip answers inside a plain attempt, with no scope around it`() {
        dashboard(1L) shouldBeRight Dashboard(User("Ada"), listOf(Order(1L)))
    }

    @Test
    fun `a failure in a branch is the attempt's Left`() {
        dashboard(2L) shouldBeLeft Bad("no user 2")
    }

    @Test
    fun `each branch fails into a Fail of its own, not into the enclosing one`() {
        val enclosing = AtomicReference<Fail<Bad>?>(null)
        val branch = AtomicReference<Fail<Bad>?>(null)

        val zipped = attempt<Bad, Int> {
            enclosing.set(this)
            parZip(
                {
                    branch.set(this)
                    20
                },
                { 22 },
            ) { a, b -> a + b }
        }

        zipped shouldBeRight 42
        // A scope never crosses a thread, so a fork gets a boundary of its own.
        (branch.get() !== enclosing.get()) shouldBe true
    }

    @Test
    fun `parZip runs five to nine branches`() {
        attempt<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e -> a + b + c + d + e }
        } shouldBeRight "abcde"
        attempt<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBeRight "abcdef"
        attempt<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            }
        } shouldBeRight "abcdefg"
        attempt<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }) { a, b, c, d, e, f, g, h ->
                a + b + c + d + e + f + g + h
            }
        } shouldBeRight "abcdefgh"
        attempt<Bad, String> {
            parZip(
                { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
            ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i }
        } shouldBeRight "abcdefghi"
    }

    @Test
    fun `parMap answers in order, and a failing element is the Left`() {
        attempt<Bad, List<Int>> { parMap(listOf(1, 2, 3)) { it * 2 } } shouldBeRight listOf(2, 4, 6)
        attempt<Bad, List<Int>> { parMap(listOf(1)) { fail(Bad("mapped")) } } shouldBeLeft Bad("mapped")
    }

    @Test
    fun `a failure is what surfaces, not what a sibling threw once the failure cut it short`() {
        val started = attempt<Bad, List<Int>> {
            parMap(listOf(0, 1)) { n ->
                if (n == 1) {
                    Thread.sleep(50)
                    fail(Bad("refused"))
                }
                // A pool that turns the interrupt into an exception of its own, as a JDBC pool starting up does.
                try {
                    Thread.sleep(60_000)
                    n
                } catch (interrupted: InterruptedException) {
                    throw IllegalStateException("pool closed by interrupt", interrupted)
                }
            }
        }

        started shouldBeLeft Bad("refused")
    }
}
