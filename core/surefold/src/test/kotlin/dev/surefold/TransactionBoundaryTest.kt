package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Test

class TransactionBoundaryTest {

    @Test
    fun `an ordinary step refuses ownership before admission body or fence publication`() {
        val admission = HandoffDecision<Unit>()
        val body = HandoffDecision<Unit>()
        val fence = HandoffDecision<Unit>()

        assertAll(
            {
                shouldThrow<IllegalStateException> {
                    transact {
                        checkNotInTransaction()
                        admission.tryComplete(Unit)
                        body.tryComplete(Unit)
                        fence.tryComplete(Unit)
                    }
                }.message shouldBe "the operation cannot run inside a transaction"
            },
            { admission.peek() shouldBe null },
            { body.peek() shouldBe null },
            { fence.peek() shouldBe null },
        )
    }

    @Test
    fun `a nested step refuses ownership before admission body or fence publication`() {
        val admission = HandoffDecision<Unit>()
        val body = HandoffDecision<Unit>()
        val fence = HandoffDecision<Unit>()

        assertAll(
            {
                shouldThrow<IllegalStateException> {
                    transact {
                        transact {
                            checkNotInTransaction()
                            admission.tryComplete(Unit)
                            body.tryComplete(Unit)
                            fence.tryComplete(Unit)
                        }
                    }
                }.message shouldBe "the operation cannot run inside a transaction"
            },
            { admission.peek() shouldBe null },
            { body.peek() shouldBe null },
            { fence.peek() shouldBe null },
        )
    }

    @Test
    fun `a solo step refuses ownership before admission body or fence publication`() {
        val admission = HandoffDecision<Unit>()
        val body = HandoffDecision<Unit>()
        val fence = HandoffDecision<Unit>()

        assertAll(
            {
                shouldThrow<IllegalStateException> {
                    transact(soloAfter = 0) {
                        checkNotInTransaction()
                        admission.tryComplete(Unit)
                        body.tryComplete(Unit)
                        fence.tryComplete(Unit)
                    }
                }.message shouldBe "the operation cannot run inside a transaction"
            },
            { admission.peek() shouldBe null },
            { body.peek() shouldBe null },
            { fence.peek() shouldBe null },
        )
    }

    @Test
    fun `outside a step ownership can pass admission body and fence`() {
        val admission = HandoffDecision<Unit>()
        val body = HandoffDecision<Unit>()
        val fence = HandoffDecision<Unit>()

        checkNotInTransaction() shouldBe Unit
        admission.tryComplete(Unit) shouldBe true
        body.tryComplete(Unit) shouldBe true
        fence.tryComplete(Unit) shouldBe true

        admission.peek() shouldBe Unit
        body.peek() shouldBe Unit
        fence.peek() shouldBe Unit
    }

    @Test
    fun `an after commit callback passes the boundary and sees the committed value`() {
        val value = Shared(1)
        val seen = HandoffDecision<Int>()

        transact {
            value.set(2)
            afterCommit {
                checkNotInTransaction() shouldBe Unit
                seen.tryComplete(value.peek()) shouldBe true
            }
        }

        seen.peek() shouldBe 2
    }

    @Test
    fun `handling refusal inside a step leaves its reads and writes available to commit`() {
        val value = Shared(1)

        transact {
            shouldThrow<IllegalStateException> { checkNotInTransaction() }.message shouldBe
                "the operation cannot run inside a transaction"
            value.set(value.get() + 1)
            value.get() shouldBe 2
        }

        value.peek() shouldBe 2
    }

    @Test
    fun `a primary failure after handled refusal keeps identity rolls back and leaves later steps usable`() {
        val value = Shared(1)
        val primary = IllegalArgumentException("primary")

        shouldThrow<IllegalArgumentException> {
            transact {
                shouldThrow<IllegalStateException> { checkNotInTransaction() }.message shouldBe
                    "the operation cannot run inside a transaction"
                value.set(value.get() + 1)
                throw primary
            }
        } shouldBeSameInstanceAs primary

        value.peek() shouldBe 1
        checkNotInTransaction() shouldBe Unit
        transact { value.set(value.get() + 2) }
        value.peek() shouldBe 3
    }

    @Test
    fun `the boundary leaves an outside callers interrupt flag unchanged`() {
        val thread = Thread.currentThread()
        val interrupted = thread.isInterrupted
        try {
            Thread.interrupted()
            checkNotInTransaction() shouldBe Unit
            thread.isInterrupted shouldBe false
            thread.interrupt()
            checkNotInTransaction() shouldBe Unit
            thread.isInterrupted shouldBe true
        } finally {
            Thread.interrupted()
            if (interrupted) thread.interrupt()
        }
    }

    @Test
    fun `refusal leaves an already entered steps interrupt flag unchanged`() {
        val thread = Thread.currentThread()
        val interrupted = thread.isInterrupted
        try {
            Thread.interrupted()
            transact {
                shouldThrow<IllegalStateException> { checkNotInTransaction() }.message shouldBe
                    "the operation cannot run inside a transaction"
                thread.isInterrupted shouldBe false
                thread.interrupt()
                shouldThrow<IllegalStateException> { checkNotInTransaction() }.message shouldBe
                    "the operation cannot run inside a transaction"
                thread.isInterrupted shouldBe true
            }
            thread.isInterrupted shouldBe true
        } finally {
            Thread.interrupted()
            if (interrupted) thread.interrupt()
        }
    }
}
