package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test

class RaceTest {

    @Test
    fun `the branch that returns first wins, on the side it was given, and the loser is interrupted`() {
        val loser = Sleeper()

        val startedAt = System.nanoTime()
        val raced =
            attempt<Bad, Either<String, Int>> {
                scope {
                    raceN(
                        { loser.losingWith("lost") },
                        {
                            loser.awaitStart()
                            42
                        },
                    )
                }
            }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        raced shouldBeRight 42.right()
        loser.wasInterrupted() shouldBe true
        withClue("the winner interrupts the loser, so the race cannot have waited out its sleep") {
            (elapsedMillis < PROMPT_MILLIS) shouldBe true
        }
        loser.isAlive() shouldBe false
    }

    @Test
    fun `a branch that fails first loses the race for everyone`() {
        val sleeper = Sleeper()

        val raced =
            attempt<Bad, Either<String, Int>> {
                scope {
                    raceN(
                        { sleeper.losingWith("lost") },
                        {
                            sleeper.awaitStart()
                            fail(Bad("broken"))
                        },
                    )
                }
            }

        raced shouldBeLeft Bad("broken")
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `a three-branch race places its winner on the side it was given`() {
        val second = Sleeper()
        val third = Sleeper()
        val firstWon =
            attempt<Bad, Either<String, Either<Int, Boolean>>> {
                scope {
                    raceN(
                        {
                            second.awaitStart()
                            third.awaitStart()
                            "won"
                        },
                        { second.losingWith(0) },
                        { third.losingWith(false) },
                    )
                }
            }
        firstWon shouldBeRight "won".left()

        val firstOfThree = Sleeper()
        val thirdOfThree = Sleeper()
        val secondWon =
            attempt<Bad, Either<String, Either<Int, Boolean>>> {
                scope {
                    raceN(
                        { firstOfThree.losingWith("lost") },
                        {
                            firstOfThree.awaitStart()
                            thirdOfThree.awaitStart()
                            42
                        },
                        { thirdOfThree.losingWith(false) },
                    )
                }
            }
        secondWon shouldBeRight 42.left().right()

        val firstLoser = Sleeper()
        val secondLoser = Sleeper()
        val thirdWon =
            attempt<Bad, Either<String, Either<Int, Boolean>>> {
                scope {
                    raceN(
                        { firstLoser.losingWith("lost") },
                        { secondLoser.losingWith(0) },
                        {
                            firstLoser.awaitStart()
                            secondLoser.awaitStart()
                            true
                        },
                    )
                }
            }
        thirdWon shouldBeRight true.right().right()
    }

    @Test
    fun `a loser that does not catch the interrupt still leaves the scope with the winner's value`() {
        val started = CountDownLatch(1)
        val loser = AtomicReference<Thread?>(null)

        val raced =
            attempt<Bad, Either<Int, String>> {
                scope {
                    raceN(
                        {
                            // Nothing here catches the interrupt, so this branch ends with an InterruptedException.
                            loser.set(Thread.currentThread())
                            started.countDown()
                            Thread.sleep(NEVER_FINISHES_MILLIS)
                            0
                        },
                        {
                            started.await()
                            "won"
                        },
                    )
                }
            }

        raced shouldBeRight "won".right()
        withClue("a scope that returns with a loser still running is a leak") {
            loser.get().shouldNotBeNull().isAlive shouldBe false
        }
    }
}
