package dev.surefold.tenancy.crypto

import dev.surefold.Counter
import dev.surefold.ExitCase
import dev.surefold.HandoffDecision
import dev.surefold.Metrics
import dev.surefold.NoMetrics
import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.SingleFlight
import dev.surefold.SystemClock
import dev.surefold.TestClock
import dev.surefold.TurnSeam
import dev.surefold.Turns
import dev.surefold.awaitValue
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldThrow
import dev.surefold.metrics
import dev.surefold.resourceScope
import dev.surefold.scope
import dev.surefold.takingTurns
import dev.surefold.test.eventually
import dev.surefold.timeout
import dev.surefold.transact
import dev.surefold.use
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

object RetainedKeyCacheSuite : Suite() {
    init {
        test("an owed release waits for an explicit drain") {
            val ledger = KeyCacheLedger()
            val calls = Shared(0)

            ledger.owe { calls.update { it + 1 } }
            calls.peek() shouldBe 0
            ledger.drain()
            calls.peek() shouldBe 1
        }

        test("a successful release is acknowledged and never replayed") {
            val ledger = KeyCacheLedger()
            val calls = Shared(0)
            ledger.owe { calls.update { it + 1 } }

            ledger.drain()
            ledger.drain()

            calls.peek() shouldBe 1
        }

        test("an action throwing after its own commit stays pending") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val present = Shared(true)
            val removals = Shared(0)
            val marker = IllegalStateException("synthetic release")
            ledger.owe {
                if (present.modify { false to it }) removals.update { it + 1 }
                val number = attempts.modify { it + 1 to it + 1 }
                if (number == 1) throw marker
            }

            shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === marker) shouldBe true }
            ledger.drain()
            ledger.drain()

            attempts.peek() shouldBe 2
            removals.peek() shouldBe 1
        }

        test("a committed acknowledgement survives instrumentation throwing") {
            val ledger = KeyCacheLedger()
            val calls = Shared(0)
            val marker = IllegalStateException("synthetic acknowledgement")
            ledger.owe { metrics.locally(NoMetrics) { calls.update { it + 1 } } }

            onLedgerCommit(0, { throw marker }) {
                shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === marker) shouldBe true }
            }
            ledger.drain()

            calls.peek() shouldBe 1
        }

        test("a committed intent remains owned when publication instrumentation throws") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val primary = IllegalStateException("synthetic publication")
            val cleanup = IllegalStateException("synthetic release")

            onLedgerCommit(0, { throw primary }) {
                shouldThrow<IllegalStateException>({
                    resourceScope {
                        onRelease { _ -> ledger.drain() }
                        ledger.owe {
                            val number = metrics.locally(NoMetrics) { attempts.modify { it + 1 to it + 1 } }
                            if (number == 1) throw cleanup
                        }
                    }
                }) {
                    (it === primary) shouldBe true
                    it.suppressed.toList() shouldBe listOf(cleanup)
                }
            }
            ledger.drain()
            ledger.drain()

            attempts.peek() shouldBe 2
        }

        test("a drain attempts every snapshot action once in insertion order") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(listOf(0, 0, 0))
            val seen = Shared<List<Int>>(emptyList())
            val failures = listOf(IllegalStateException("synthetic first"), IllegalStateException("synthetic second"))
            repeat(3) { index ->
                ledger.owe {
                    seen.update { it + index }
                    val number = attempts.modify { counts ->
                        val next = counts.mapIndexed { position, count -> if (position == index) count + 1 else count }
                        next to next[index]
                    }
                    if (index < failures.size && number == 1) throw failures[index]
                }
            }

            shouldThrow<IllegalStateException>({ ledger.drain() }) {
                (it === failures[0]) shouldBe true
                it.suppressed.toList() shouldBe listOf(failures[1])
            }
            seen.peek() shouldBe listOf(0, 1, 2)
            attempts.peek() shouldBe listOf(1, 1, 1)
            ledger.drain()
            ledger.drain()

            seen.peek() shouldBe listOf(0, 1, 2, 0, 1)
            attempts.peek() shouldBe listOf(2, 2, 1)
        }

        test("work queued during a drain waits for the next drain") {
            val ledger = KeyCacheLedger()
            val first = Shared(0)
            val later = Shared(0)
            ledger.owe {
                first.update { it + 1 }
                ledger.owe { later.update { it + 1 } }
            }

            ledger.drain()
            first.peek() shouldBe 1
            later.peek() shouldBe 0
            ledger.drain()
            ledger.drain()

            first.peek() shouldBe 1
            later.peek() shouldBe 1
        }

        test("another successful acknowledgement cannot be undone by a failed attempt") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val entered = Promise<Nothing, Unit>()
            val finish = Promise<Nothing, Unit>()
            val marker = IllegalStateException("synthetic held attempt")
            ledger.owe {
                val number = attempts.modify { it + 1 to it + 1 }
                if (number == 1) {
                    entered.succeed(Unit)
                    finish.awaitValue()
                    throw marker
                }
            }
            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    scope {
                        val first = async {
                            shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === marker) shouldBe true }
                        }
                        try {
                            entered.awaitValue()
                            ledger.drain()
                        } finally {
                            finish.succeed(Unit)
                        }
                        first.await()
                    }
                }
            }
            ledger.drain()

            attempts.peek() shouldBe 2
        }

        test("a stale acknowledgement cannot remove a new intent using the same action") {
            val ledger = KeyCacheLedger()
            val attempts = Shared(0)
            val entered = Promise<Nothing, Unit>()
            val finish = Promise<Nothing, Unit>()
            val action: () -> Unit = {
                val number = attempts.modify { it + 1 to it + 1 }
                if (number == 1) {
                    entered.succeed(Unit)
                    finish.awaitValue()
                }
            }
            ledger.owe(action)
            clock.locally(SystemClock) {
                timeout(10.seconds) {
                    scope {
                        val first = async { ledger.drain() }
                        try {
                            entered.awaitValue()
                            ledger.drain()
                            ledger.owe(action)
                        } finally {
                            finish.succeed(Unit)
                        }
                        first.await()
                    }
                }
            }
            attempts.peek() shouldBe 2
            ledger.drain()
            ledger.drain()

            attempts.peek() shouldBe 3
        }

        test("a legacy source remains owned until its final receipt leaves") { normalGroupSource(qualified = false) }

        test("a qualified source remains owned until its final receipt leaves") { normalGroupSource(qualified = true) }

        test("a delayed enrolled receipt replays and keeps the frozen source owned") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
                val loads = Shared(0)
                resourceScope {
                    val delayed = ledger.enrol(key, captured, groupDeadline).bind()
                    runGroup(ledger, flights, key, captured) {
                        loads.update { it + 1 }
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    } shouldBe Either.Right(Unit)
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    flights.run(delayed.group) {
                        ledger.settle(delayed) {
                            loads.update { it + 1 }
                            Either.Left(KeyError.Unavailable("synthetic duplicate load"))
                        }
                    } shouldBe Either.Right(Unit)
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    loads.peek() shouldBe 1
                }
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("frozen refusal serves only old receipts while a new caller loads again") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val loads = Shared(0)
                val refusal = Either.Left(KeyError.Unavailable("synthetic refusal"))
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    flights.run(first.group) {
                        ledger.settle(first) { loads.update { it + 1 }; refusal }
                    } shouldBe refusal
                    flights.run(delayed.group) {
                        ledger.settle(delayed) { loads.update { it + 1 }; refusal }
                    } shouldBe refusal
                    loads.peek() shouldBe 1
                    runGroup(ledger, flights, key, clock) {
                        loads.update { it + 1 }
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    } shouldBe Either.Right(Unit)
                    loads.peek() shouldBe 2
                    source.all { it == 0.toByte() } shouldBe true
                }
            }
        }

        test("a delayed receipt replays the exact loader cancellation exception") {
            withLoadGroups { ledger, flights, key, clock ->
                val loads = Shared(0)
                val marker = CancellationException("synthetic loader cancellation")
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    listOf(first, delayed).forEach { receipt ->
                        shouldThrow<CancellationException>({
                            flights.run(receipt.group) {
                                ledger.settle(receipt) { loads.update { it + 1 }; throw marker }
                            }
                        }) { (it === marker) shouldBe true }
                    }
                    loads.peek() shouldBe 1
                }
            }
        }

        test("a committed enrolment fault before run leaves no reserved loader") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val entered = Shared(false)
                val marker = IllegalStateException("synthetic enrolment")
                onLedgerCommit(0, { throw marker }) {
                    shouldThrow<IllegalStateException>({
                        ledger.enrol(key, clock, groupDeadline).use {
                            entered.update { true }
                        }
                    }) { (it === marker) shouldBe true }
                }
                entered.peek() shouldBe false
                runGroup(ledger, flights, key, clock) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                } shouldBe Either.Right(Unit)
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("closed SingleFlight never leaves an unentered group owning a loader") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val closed = SingleFlight<Any, KeyError, Unit>()
                closed.closeAndJoin()
                val loads = Shared(0)
                shouldThrow<IllegalStateException>({
                    runGroup(ledger, closed, key, clock) {
                        loads.update { it + 1 }
                        Either.Left(KeyError.Unavailable("must not run"))
                    }
                }) { it.message shouldBe "SingleFlight is closed" }
                loads.peek() shouldBe 0
                runGroup(ledger, flights, key, clock) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                } shouldBe Either.Right(Unit)
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a worker claim fault closes the old group before invoking the supplier") {
            withLoadGroups { ledger, flights, key, clock ->
                val loads = Shared(0)
                val marker = IllegalStateException("synthetic claim")
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    onLedgerCommit(0, { throw marker }) {
                        shouldThrow<IllegalStateException>({
                            flights.run(first.group) {
                                ledger.settle(first) {
                                    metrics.locally(NoMetrics) { loads.update { it + 1 } }
                                    Either.Left(KeyError.Unavailable("must not load"))
                                }
                            }
                        }) { (it === marker) shouldBe true }
                    }
                    shouldThrow<IllegalStateException>({
                        flights.run(delayed.group) {
                            ledger.settle(delayed) {
                                loads.update { it + 1 }
                                Either.Left(KeyError.Unavailable("must replay the claim fault"))
                            }
                        }
                    }) { (it === marker) shouldBe true }
                    loads.peek() shouldBe 0
                }
            }
        }

        test("committed Value remains owned when its publication metrics throws") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, key, clock ->
                val returned = Shared(false)
                val loads = Shared(0)
                val marker = IllegalStateException("synthetic outcome publication")
                resourceScope {
                    val first = ledger.enrol(key, clock, groupDeadline).bind()
                    val delayed = ledger.enrol(key, clock, groupDeadline).bind()
                    failCommitAfter(returned, marker) {
                        shouldThrow<IllegalStateException>({
                            flights.run(first.group) {
                                ledger.settle(first) {
                                    metrics.locally(NoMetrics) {
                                        loads.update { it + 1 }
                                        returned.update { true }
                                    }
                                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                                }
                            }
                        }) { (it === marker) shouldBe true }
                    }
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    flights.run(delayed.group) {
                        ledger.settle(delayed) {
                            loads.update { it + 1 }
                            Either.Left(KeyError.Unavailable("must replay committed Value"))
                        }
                    } shouldBe Either.Right(Unit)
                    source.toList() shouldBe listOf<Byte>(3, 4, 5)
                    loads.peek() shouldBe 1
                }
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("direct reentry refuses before waiting on its own flight") { recursiveGroup(indirect = false) }
        test("inherited indirect reentry refuses before waiting on the outer flight") {
            recursiveGroup(indirect = true)
        }

        test("a nested new epoch is a different group and may load") {
            val outer = byteArrayOf(3, 4, 5)
            val inner = byteArrayOf(6, 7, 8)
            withLoadGroups(listOf(outer, inner)) { ledger, flights, key, captured ->
                val nested = key.copy(epoch = Any())
                runGroup(ledger, flights, key, captured) {
                    runGroup(ledger, flights, nested, captured) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, inner))
                    } shouldBe Either.Right(Unit)
                    inner.all { it == 0.toByte() } shouldBe true
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, outer))
                } shouldBe Either.Right(Unit)
                outer.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a claimed loader survives its last waiting caller and serves the next receipt") {
            val source = byteArrayOf(3, 4, 5)
            val duplicate = byteArrayOf(6, 7, 8)
            withLoadGroups(listOf(source, duplicate)) { ledger, flights, key, captured ->
                val loads = Shared(0)
                val entered = Promise<Nothing, Unit>()
                val release = Promise<Nothing, Unit>()
                val nextEnrolled = Promise<Nothing, Unit>()
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        scope {
                            val first = async {
                                runGroup(ledger, flights, key, captured) {
                                    loads.update { it + 1 }
                                    entered.succeed(Unit)
                                    release.awaitValue()
                                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                                }
                            }
                            try {
                                entered.awaitValue()
                                first.cancel()
                                val next = async {
                                    ledger.enrol(key, captured, groupDeadline).use { receipt ->
                                        nextEnrolled.succeed(Unit)
                                        flights.run(receipt.group) {
                                            ledger.settle(receipt) {
                                                loads.update { it + 1 }
                                                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, duplicate))
                                            }
                                        }
                                        source.toList()
                                    }
                                }
                                nextEnrolled.awaitValue()
                                release.succeed(Unit)
                                next.await() shouldBe listOf<Byte>(3, 4, 5)
                            } finally {
                                release.succeed(Unit)
                                first.cancel()
                            }
                        }
                    }
                }
                flights.closeAndJoin()
                loads.peek() shouldBe 1
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a retired pre-entry group cannot load or disturb a newer same-key group") {
            val freshSource = byteArrayOf(6, 7, 8)
            withLoadGroups(listOf(freshSource)) { ledger, flights, key, captured ->
                val oldLoads = Shared(0)
                val retired = Shared(false)
                val entered = Promise<Nothing, Unit>()
                val release = Promise<Nothing, Unit>()
                val ended = Promise<Nothing, Unit>()
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        scope {
                            val first = async {
                                ledger.enrol(key, captured, groupDeadline).use { receipt ->
                                    flights.run(receipt.group) {
                                        entered.succeed(Unit)
                                        release.awaitValue()
                                        try {
                                            ledger.settle(receipt) {
                                                oldLoads.update { it + 1 }
                                                Either.Left(KeyError.Unavailable("retired supplier must not run"))
                                            }
                                        } catch (cancelled: CancellationException) {
                                            cancelled.message shouldBe "the key load is no longer admitted"
                                            retired.update { true }
                                            throw cancelled
                                        } finally {
                                            ended.succeed(Unit)
                                        }
                                    }
                                }
                            }
                            try {
                                entered.awaitValue()
                                first.cancel()
                                ledger.enrol(key, captured, groupDeadline).use { fresh ->
                                    flights.run(fresh.group) {
                                        ledger.settle(fresh) {
                                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, freshSource))
                                        }
                                    } shouldBe Either.Right(Unit)
                                    release.succeed(Unit)
                                    ended.awaitValue()
                                    retired.peek() shouldBe true
                                    oldLoads.peek() shouldBe 0
                                    freshSource.toList() shouldBe listOf<Byte>(6, 7, 8)
                                }
                            } finally {
                                release.succeed(Unit)
                                first.cancel()
                            }
                        }
                    }
                }
                flights.closeAndJoin()
                freshSource.all { it == 0.toByte() } shouldBe true
            }
        }

        test("claim bookkeeping cannot recursively wait on its own group") {
            withLoadGroups { ledger, flights, key, captured ->
                val loads = Shared(0)
                resourceScope {
                    val receipt = ledger.enrol(key, captured, groupDeadline).bind()
                    onLedgerCommit(0, {
                        runGroup(ledger, flights, key, captured) { error("recursive supplier must not enter") }
                    }) {
                        shouldThrow<IllegalStateException>({
                            clock.locally(SystemClock) {
                                timeout(1.seconds) {
                                    flights.run(receipt.group) {
                                        ledger.settle(receipt) {
                                            loads.update { it + 1 }
                                            Either.Left(KeyError.Unavailable("must not load"))
                                        }
                                    }
                                }
                            }
                        }) { it.message shouldBe "recursive key load" }
                    }
                    loads.peek() shouldBe 0
                }
            }
        }

        test("qualified metadata refusal wipes the transferred source without publishing a delivery") {
            val source = byteArrayOf(3, 4, 5)
            withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
                val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
                val answer = runGroup(ledger, flights, legacy.copy(identity = identity), captured) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Bidx, 1, source))
                }
                (answer is Either.Left && answer.value is KeyError.Invalid) shouldBe true
                source.all { it == 0.toByte() } shouldBe true
            }
        }
    }
}

internal object RetainedKeyDeliverySuite : Suite(concurrent = false, isolated = true) {
    init {
        test("a returned copy survives release of its source") { returnedCopySurvives() }
        test("a returned view survives forgetting its source") { returnedViewSurvives() }
        test("receipt cleanup failure wipes a legacy copy that was never returned") { abandonedCopyWipes() }
        test("receipt cleanup failure closes a qualified view that was never returned") { abandonedViewCloses() }
        test("a preparing copy survives a drain before return") { preparingDrain(false) }
        test("a preparing view survives a drain before admission") { preparingDrain(true) }
        test("a delivered copy survives metadata reap failure") { deliveredReapFault(false) }
        test("a delivered view survives metadata reap failure") { deliveredReapFault(true) }
        test("a committed copy registration remains owned when its observer throws") {
            registrationObserverFault(false)
        }
        test("a committed view registration remains owned when its observer throws") { registrationObserverFault(true) }
        test("an admitted view remains owned when admission instrumentation throws") { admissionObserverFault() }
        test("an abort transfer observer failure still releases its exact view") { abortObserverFault("transfer") }
        test("an abort action observer failure stays pending for later drain") { abortObserverFault("action") }
        test("a committed abort acknowledgement is never resurrected") { abortObserverFault("ack") }
        test("stale prepared cleanup leaves a newer same-key delivery intact") { stalePreparedCleanup() }
        test("a typed refusal never becomes a delivered value") { leftIsNotDelivery() }
        test("receipt cancellation aborts the copy with its original identity") { cancellationAbortsCopy() }
        test("failed abort transfer and close before commit keep the exact capability owned") {
            bothAbortPrecommitFailures()
        }
        test("delivery supplier recursion refuses without orphaning another receipt") { deliveryRecursionRefuses() }
        test("copy registration interruption before commit releases its local ownership") {
            registrationPrecommitFault(false)
        }
        test("view registration interruption before commit admits no view") { registrationPrecommitFault(true) }
    }
}

object RetainedKeyCallersSuite : Suite(concurrent = false, isolated = true) {
    init {
        test("an accepted body owns its Resource release before call returns") {
            val callers = KeyCallers()
            val events = Shared(emptyList<String>())
            callers.call {
                resourceScope {
                    onRelease { _ -> events.update { it + "release" } }
                    events.update { it + "body" }
                }
            }
            events.update { it + "returned" }
            events.peek() shouldBe listOf("body", "release", "returned")
        }

        test("a committed admission observer throw preserves original and runs no body") {
            val callers = KeyCallers()
            val marker = IllegalStateException("synthetic admission observer")
            val body = HandoffDecision<Unit>()
            atDeliveryCommit({ stack -> stack.any { it.methodName == "admit" } }, { throw marker }) {
                shouldThrow<IllegalStateException>({ callers.call { body.tryComplete(Unit) } }) {
                    (it === marker) shouldBe true
                }
            }
            body.peek() shouldBe null
            callers.call { body.tryComplete(Unit) }
            body.peek() shouldBe Unit
        }

        test("ordinary interrupt and cancellation retain ordered owned releases") {
            val ordinary = IllegalStateException("synthetic caller")
            val interrupted = InterruptedException("synthetic caller")
            val cancelled = CancellationException("synthetic caller")
            listOf(
                ordinary to ExitCase.Failure(ordinary),
                interrupted to ExitCase.Cancelled(interrupted),
                cancelled to ExitCase.Completed,
            ).forEach { (primary, exit) -> callerFailure(primary, exit) }
        }

        test("a suppression-disabled original retains identity and all ordered release attempts") {
            val callers = KeyCallers()
            val primary = object : Throwable("synthetic caller", null, false, false) {}
            val first = IllegalStateException("synthetic outer release")
            val second = IllegalStateException("synthetic inner release")
            val releases = Shared(emptyList<Pair<Int, ExitCase>>())
            shouldThrow<Throwable>({
                callers.call {
                    resourceScope {
                        onRelease { exit ->
                            releases.update { it + (1 to exit) }
                            throw first
                        }
                        onRelease { exit ->
                            releases.update { it + (2 to exit) }
                            throw second
                        }
                        throw primary
                    }
                }
            }) { (it === primary) shouldBe true }
            releases.peek() shouldBe listOf(2 to ExitCase.Failure(primary), 1 to ExitCase.Failure(primary))
            primary.suppressed.toList() shouldBe emptyList()
        }

        test("a release failure after normal body remains the original failure") {
            val callers = KeyCallers()
            val primary = IllegalStateException("synthetic release")
            val exits = Shared(emptyList<ExitCase>())
            shouldThrow<IllegalStateException>({
                callers.call {
                    resourceScope {
                        onRelease { exit ->
                            exits.update { it + exit }
                            throw primary
                        }
                    }
                }
            }) { (it === primary) shouldBe true }
            exits.peek() shouldBe listOf(ExitCase.Completed)
        }

        test("an opaque Fail crosses caller and foreign attempt with cleanup intact") {
            val callers = KeyCallers()
            val error = IllegalArgumentException("synthetic declared error")
            val prior = IllegalStateException("synthetic prior diagnostic")
            val first = IllegalStateException("synthetic outer release")
            val second = IllegalStateException("synthetic inner release")
            val original = HandoffDecision<Throwable>()
            val foreignTail = HandoffDecision<Unit>()
            val events = Shared(emptyList<Int>())
            val exits = Shared(emptyList<ExitCase>())
            error.addSuppressed(prior)
            val answer = attempt<Throwable, Unit> {
                val owner = this
                shouldThrow<Throwable>({
                    attempt<String, Unit> {
                        callers.call {
                            resourceScope {
                                onRelease { exit ->
                                    events.update { it + 1 }
                                    exits.update { it + exit }
                                    throw first
                                }
                                onRelease { exit ->
                                    events.update { it + 2 }
                                    exits.update { it + exit }
                                    throw second
                                }
                                shouldThrow<Throwable>({ owner.fail(error) }) { signal ->
                                    original.tryComplete(signal)
                                    signal.stackTrace.toList() shouldBe emptyList()
                                    throw signal
                                }
                            }
                        }
                        foreignTail.tryComplete(Unit)
                    }
                }) { crossed ->
                    (crossed === original.peek()) shouldBe true
                    crossed.stackTrace.toList() shouldBe emptyList()
                    crossed.suppressed.toList() shouldBe listOf(second, first)
                    throw crossed
                }
            }
            answer.shouldBeLeft { (it === error) shouldBe true }
            error.suppressed.toList() shouldBe listOf(prior)
            events.peek() shouldBe listOf(2, 1)
            exits.peek() shouldBe listOf(ExitCase.Completed, ExitCase.Completed)
            foreignTail.peek() shouldBe null
        }

        test("the call guard refuses ordinary nested and forced solo steps before admission") {
            listOf("ordinary", "nested", "solo").forEach { mode -> callerGuard(mode) }
        }

        test("call refusal preserves an already entered step and interrupt flag") {
            val callers = KeyCallers()
            val thread = Thread.currentThread()
            val before = thread.isInterrupted
            val value = Shared(0)
            val body = HandoffDecision<Unit>()
            try {
                Thread.interrupted()
                transact {
                    thread.interrupt()
                    shouldThrow<IllegalStateException>({ callers.call { body.tryComplete(Unit) } }) {
                        it.message shouldBe "the operation cannot run inside a transaction"
                    }
                    thread.isInterrupted shouldBe true
                    Thread.interrupted()
                    value.set(1)
                }
                value.peek() shouldBe 1
                body.peek() shouldBe null
            } finally {
                Thread.interrupted()
                if (before) thread.interrupt()
            }
        }

        test("caller failure preserves a cyclic existing suppressed graph") {
            val callers = KeyCallers()
            val primary = IllegalStateException("synthetic primary")
            val nested = IllegalStateException("synthetic nested")
            val release = IllegalStateException("synthetic release")
            primary.addSuppressed(nested)
            nested.addSuppressed(primary)
            shouldThrow<IllegalStateException>({
                callers.call {
                    resourceScope {
                        onRelease { _ -> throw release }
                        throw primary
                    }
                }
            }) {
                (it === primary) shouldBe true
                it.suppressed.toList() shouldBe listOf(nested, release)
                nested.suppressed.toList() shouldBe listOf(primary)
            }
        }

        test("the Solo probe interrupts a real pending Shared commit without changing its value") {
            val probe = CallerFenceProbe()
            val state = Shared(0)
            val failed = HandoffDecision<InterruptedException>()
            withCallerFenceContention(probe, { state.peek() shouldBe 0 }) {
                shouldThrow<InterruptedException>({ state.update { it + 1 } }) {
                    failed.tryComplete(it)
                    it.stackTrace.any { frame -> frame.methodName == "awaitFree" } shouldBe true
                    it.stackTrace.any { frame -> frame.methodName == "commit\$dev_surefold_surefold" } shouldBe true
                }
                Thread.currentThread().isInterrupted shouldBe false
            }
            (failed.peek() != null) shouldBe true
            state.peek() shouldBe 0
            state.update { it + 1 }
            state.peek() shouldBe 1
            probe.target.awaitValue().isAlive shouldBe false
            probe.holder.awaitValue().isAlive shouldBe false
        }

        test("a fresh commit after the Solo interruption survives its observer failure and owned unwind") {
            val probe = CallerFenceProbe()
            val state = Shared(0)
            val observed = HandoffDecision<Int>()
            val released = HandoffDecision<ExitCase>()
            val marker = IllegalStateException("synthetic probe observer")
            withCallerFenceContention(probe, { state.peek() shouldBe 0 }) {
                shouldThrow<InterruptedException>({ state.update { it + 1 } }) {
                    it.stackTrace.any { frame -> frame.methodName == "awaitFree" } shouldBe true
                }
                shouldThrow<IllegalStateException>({
                    resourceScope {
                        onRelease { exit -> released.tryComplete(exit) }
                        atDeliveryCommit({ stack -> stack.any { it.methodName == "modify" } }, {
                            observed.tryComplete(state.peek())
                            throw marker
                        }) { state.modify { it + 1 to Unit } }
                    }
                }) { (it === marker) shouldBe true }
            }
            observed.peek() shouldBe 1
            state.peek() shouldBe 1
            released.peek() shouldBe ExitCase.Failure(marker)
        }

        test("an assertion failure while Solo is held releases every gate and joins both workers") {
            val probe = CallerFenceProbe()
            val state = Shared(0)
            val marker = AssertionError("synthetic probe assertion")
            shouldThrow<AssertionError>({
                withCallerFenceContention(probe, { throw marker }) { state.update { it + 1 } }
            }) { (it === marker) shouldBe true }
            probe.target.awaitValue().isAlive shouldBe false
            probe.holder.awaitValue().isAlive shouldBe false
            probe.blocker.sleepers() shouldBe 0
            probe.holderStart.sleepers() shouldBe 0
            probe.targetStart.sleepers() shouldBe 0
            probe.pause.sleepers() shouldBe 0
            state.peek() shouldBe 0
            state.update { it + 1 }
            state.peek() shouldBe 1
        }
    }
}

private fun <A> onLedgerCommit(target: Int, action: () -> Unit, block: () -> A): A {
    val calls = Shared(0)
    val atCommit = object : Metrics by NoMetrics {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
            if (name == "surefold.transact.runs" && tags["outcome"] == "committed") {
                val index = metrics.locally(NoMetrics) { calls.modify { it + 1 to it } }
                if (index == target) metrics.locally(NoMetrics, action)
            }
        }
    }
    return metrics.locally(atCommit, block)
}

private fun runGroup(
    ledger: KeyCacheLedger,
    flights: SingleFlight<Any, KeyError, Unit>,
    key: KeyCacheLedger.LoadKey,
    clock: TestClock,
    load: () -> Either<KeyError, DataKey>,
): Either<KeyError, Unit> = ledger.enrol(key, clock, groupDeadline).use { receipt ->
    flights.run(receipt.group) { ledger.settle(receipt, load) }
}

private val groupDeadline: Instant = Instant.EPOCH.plusSeconds(3_600)

private fun <A> withLoadGroups(
    arrays: List<ByteArray> = emptyList(),
    block: (KeyCacheLedger, SingleFlight<Any, KeyError, Unit>, KeyCacheLedger.LoadKey, TestClock) -> A,
): A = resourceScope {
    onRelease { _ -> arrays.forEach { it.fill(0) } }
    val flights = SingleFlight<Any, KeyError, Unit>()
    onRelease { _ -> flights.closeAndJoin() }
    val key = KeyCacheLedger.LoadKey(KeyCache.Slot(KeyOwner.Platform, KeyPurpose.Enc, 1), null, Any())
    block(KeyCacheLedger(), flights, key, TestClock(Instant.EPOCH))
}

private fun recursiveGroup(indirect: Boolean) = withLoadGroups { ledger, flights, key, captured ->
    val nested = key.copy(slot = key.slot.copy(purpose = KeyPurpose.Bidx))
    val loads = Shared(0)
    val recurse: () -> Either<KeyError, DataKey> = {
        loads.update { it + 1 }
        runGroup(ledger, flights, key, captured) { error("recursive supplier must not enter") }
        error("recursive group must refuse")
    }
    shouldThrow<IllegalStateException>({
        clock.locally(SystemClock) {
            timeout(1.seconds) {
                runGroup(ledger, flights, key, captured) {
                    loads.update { it + 1 }
                    if (indirect) runGroup(ledger, flights, nested, captured, recurse)
                    else runGroup(ledger, flights, key, captured) { error("recursive supplier must not enter") }
                    error("recursive group must refuse")
                }
            }
        }
    }) { it.message shouldBe "recursive key load" }
    loads.peek() shouldBe if (indirect) 2 else 1
}

private fun <A> failCommitAfter(ready: Shared<Boolean>, marker: Throwable, block: () -> A): A {
    val fired = Shared(false)
    val failing = object : Metrics by NoMetrics {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
            val committed = name == "surefold.transact.runs" && tags["outcome"] == "committed"
            if (committed && ready.peek() && metrics.locally(NoMetrics) { fired.modify { true to it } }.not()) {
                throw marker
            }
        }
    }
    return metrics.locally(failing, block)
}

private fun normalGroupSource(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val identity = if (qualified) KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1)) else null
        val key = legacy.copy(identity = identity)
        resourceScope {
            val receipt = ledger.enrol(key, captured, groupDeadline).bind()
            flights.run(receipt.group) {
                ledger.settle(receipt) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            } shouldBe Either.Right(Unit)
            source.toList() shouldBe listOf<Byte>(3, 4, 5)
        }
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun returnedCopySurvives() {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        val answer = ledger.deliver(key, captured, groupDeadline, flights) {
            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
        }
        val copy = ((answer as Either.Right).value as KeyCacheLedger.Delivery.Copy).key
        resourceScope {
            onRelease { _ -> copy.material.fill(0) }
            source.all { it == 0.toByte() } shouldBe true
            copy.material.toList() shouldBe listOf<Byte>(3, 4, 5)
            ledger.drain()
            copy.material.toList() shouldBe listOf<Byte>(3, 4, 5)
        }
    }
}

private fun returnedViewSurvives() {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
        val answer = ledger.deliver(legacy.copy(identity = identity), captured, groupDeadline, flights) {
            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
        }
        val view = ((answer as Either.Right).value as KeyCacheLedger.Delivery.View).view
        resourceScope {
            onRelease { _ -> view.close() }
            view.using { it.toList() } shouldBe Either.Right(listOf<Byte>(3, 4, 5))
            ledger.drain()
            view.using { it.toList() } shouldBe Either.Right(listOf<Byte>(3, 4, 5))
            view.close()
            source.all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun abandonedCopyWipes() {
    val source = byteArrayOf(3, 4, 5)
    var seen: ByteArray? = null
    val marker = IllegalStateException("synthetic receipt cleanup")
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        resourceScope {
            onRelease { _ -> seen?.fill(0) }
            shouldThrow<IllegalStateException>({
                failPreparedReceipt(ledger, marker, { seen = it }) {
                    ledger.deliver(key, captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
            }) { (it === marker) shouldBe true }
            val copy = checkNotNull(seen)
            (copy !== source) shouldBe true
            copy.all { it == 0.toByte() } shouldBe true
            source.all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun abandonedViewCloses() {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic receipt cleanup")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val identity = KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1))
        shouldThrow<IllegalStateException>({
            failPreparedReceipt(ledger, marker) {
                ledger.deliver(legacy.copy(identity = identity), captured, groupDeadline, flights) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            }
        }) { (it === marker) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun <A> failPreparedReceipt(
    ledger: KeyCacheLedger,
    marker: Throwable,
    observeCopy: ((ByteArray) -> Unit)? = null,
    block: () -> A,
): A {
    val target = Thread.currentThread()
    var captured = false
    var fired = false
    val previous = metrics.get()
    val failing = object : Metrics by previous {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter { count ->
            previous.counter(name, tags).increment(count)
            val committed = name == "surefold.transact.runs" && tags["outcome"] == "committed"
            if (Thread.currentThread() === target && committed) {
                val shared = privateField(ledger, "state") as? Shared<*> ?: throw DeliveryProbeSetupFailure()
                val rows = privateField(shared.peek(), "prepared") as? List<*> ?: throw DeliveryProbeSetupFailure()
                if (rows.isNotEmpty()) {
                    if (!captured && observeCopy != null) {
                        observeCopy(snapshotPreparedCopy(rows))
                        captured = true
                    }
                    if (!fired && target.stackTrace.any { it.methodName == "finishReceipt" }) {
                        fired = true
                        throw marker
                    }
                }
            }
        }
    }
    return metrics.locally(failing, block)
}

private fun snapshotPreparedCopy(rows: List<*>): ByteArray {
    if (rows.size != 1) throw DeliveryProbeSetupFailure()
    val action = privateField(privateField(checkNotNull(rows.single()), "abort"), "action")
    val captures = action.javaClass.declaredFields.filter {
        it.type == ByteArray::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    }
    if (captures.size != 1 || !captures.single().trySetAccessible()) throw DeliveryProbeSetupFailure()
    val bytes = captures.single().get(action) as ByteArray
    if (bytes.toList() != listOf<Byte>(3, 4, 5)) throw DeliveryProbeSetupFailure()
    return bytes
}

private fun privateField(owner: Any?, name: String): Any = try {
    if (owner == null) throw DeliveryProbeSetupFailure()
    val field = owner.javaClass.getDeclaredField(name)
    if (!field.trySetAccessible()) throw DeliveryProbeSetupFailure()
    field.get(owner) ?: throw DeliveryProbeSetupFailure()
} catch (_: ReflectiveOperationException) {
    throw DeliveryProbeSetupFailure()
}

private class DeliveryProbeSetupFailure : IllegalStateException("synthetic delivery probe setup failed")

private fun preparingDrain(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val key = deliveryKey(legacy, qualified)
        var drained = false
        val answer = atDeliveryCommit({ stack -> stack.any { it.methodName == "registerPrepared" } }, {
            ledger.drain()
            drained = true
        }) {
            ledger.deliver(key, captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            }
        }
        resourceScope {
            val delivery = (answer as Either.Right).value
            onRelease { _ -> discardDelivery(delivery) }
            drained shouldBe true
            deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
        }
    }
}

private fun deliveredReapFault(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic delivered metadata")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val delivery = (
            ledger.deliver(deliveryKey(legacy, qualified), captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            } as Either.Right
            ).value
        resourceScope {
            onRelease { _ -> discardDelivery(delivery) }
            shouldThrow<IllegalStateException>({
                atDeliveryCommit({ stack -> stack.any { it.methodName == "drain" } }, { throw marker }) {
                    ledger.drain()
                }
            }) { (it === marker) shouldBe true }
            deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
            ledger.drain()
            deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
        }
    }
}

private fun registrationObserverFault(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic prepared registration")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        shouldThrow<IllegalStateException>({
            atDeliveryCommit({ stack -> stack.any { it.methodName == "registerPrepared" } }, { throw marker }) {
                ledger.deliver(deliveryKey(legacy, qualified), captured, groupDeadline, flights) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            }
        }) { (it === marker) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
    }
}

private fun admissionObserverFault() {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic view admission")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        shouldThrow<IllegalStateException>({
            atDeliveryCommit({ stack ->
                stack.any { it.className.endsWith("OwnedKeyMaterial") && it.methodName == "borrow" } &&
                    stack.none { it.methodName == "registerPrepared" }
            }, { throw marker }) {
                ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
            }
        }) { (it === marker) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
    }
}

private fun abortObserverFault(stage: String) {
    val source = byteArrayOf(3, 4, 5)
    val original = IllegalStateException("synthetic receipt cleanup")
    val cleanup = IllegalStateException("synthetic abort observer")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        shouldThrow<IllegalStateException>({
            atDeliveryCommit({ stack ->
                val aborting = stack.any { it.methodName == "abortPrepared" }
                val acknowledging = stack.any { it.methodName == "acknowledge" }
                val material = stack.any { it.className.endsWith("OwnedKeyMaterial") }
                when (stage) {
                    "transfer" -> aborting && !acknowledging && !material
                    "action" -> aborting && material
                    "ack" -> aborting && acknowledging
                    else -> throw DeliveryProbeSetupFailure()
                }
            }, { throw cleanup }) {
                failPreparedReceipt(ledger, original) {
                    ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
            }
        }) {
            (it === original) shouldBe true
            it.suppressed.toList() shouldBe listOf(cleanup)
        }
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
        val unexpected = IllegalStateException("synthetic replay after acknowledgement")
        atDeliveryCommit({ true }, { throw unexpected }) { ledger.drain() }
    }
}

private fun stalePreparedCleanup() {
    val oldSource = byteArrayOf(3, 4, 5)
    val newSource = byteArrayOf(6, 7, 8)
    val marker = IllegalStateException("synthetic stale metadata cleanup")
    withLoadGroups(listOf(oldSource, newSource)) { ledger, flights, key, captured ->
        val old = (
            ledger.deliver(key, captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, oldSource))
            } as Either.Right
            ).value
        var newer: KeyCacheLedger.Delivery? = null
        resourceScope {
            onRelease { _ -> discardDelivery(old) }
            onRelease { _ -> newer?.let { discardDelivery(it) } }
            shouldThrow<IllegalStateException>({
                atDeliveryCommit({ stack -> stack.any { it.methodName == "drain" } }, {
                    newer = (
                        ledger.deliver(key, captured, groupDeadline, flights) {
                            Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, newSource))
                        } as Either.Right
                        ).value
                    throw marker
                }) { ledger.drain() }
            }) { (it === marker) shouldBe true }
            ledger.drain()
            deliveryBytes(old) shouldBe listOf<Byte>(3, 4, 5)
            deliveryBytes(checkNotNull(newer)) shouldBe listOf<Byte>(6, 7, 8)
        }
    }
}

private fun leftIsNotDelivery() {
    val refusal = KeyError.Denied("synthetic refused key")
    withLoadGroups { ledger, flights, key, captured ->
        ledger.deliver(key, captured, groupDeadline, flights) { Either.Left(refusal) } shouldBe Either.Left(refusal)
        ledger.drain()
    }
}

private fun cancellationAbortsCopy() {
    val source = byteArrayOf(3, 4, 5)
    var seen: ByteArray? = null
    val original = CancellationException("synthetic receipt cancellation")
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        resourceScope {
            onRelease { _ -> seen?.fill(0) }
            shouldThrow<CancellationException>({
                failPreparedReceipt(ledger, original, { seen = it }) {
                    ledger.deliver(key, captured, groupDeadline, flights) {
                        Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                    }
                }
            }) { (it === original) shouldBe true }
            checkNotNull(seen).all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun deliveryKey(key: KeyCacheLedger.LoadKey, qualified: Boolean): KeyCacheLedger.LoadKey =
    key.copy(identity = if (qualified) KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1)) else null)

private fun deliveryBytes(delivery: KeyCacheLedger.Delivery): List<Byte> = when (delivery) {
    is KeyCacheLedger.Delivery.Copy -> delivery.key.material.toList()
    is KeyCacheLedger.Delivery.View -> (delivery.view.using { it.toList() } as Either.Right).value
}

private fun discardDelivery(delivery: KeyCacheLedger.Delivery) = when (delivery) {
    is KeyCacheLedger.Delivery.Copy -> delivery.key.material.fill(0)
    is KeyCacheLedger.Delivery.View -> delivery.view.close()
}

private fun <A> atDeliveryCommit(
    matches: (Array<StackTraceElement>) -> Boolean,
    action: () -> Unit,
    block: () -> A,
): A {
    val target = Thread.currentThread()
    var fired = false
    val previous = metrics.get()
    val observer = object : Metrics by previous {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter { count ->
            previous.counter(name, tags).increment(count)
            val committed = name == "surefold.transact.runs" && tags["outcome"] == "committed"
            val targetCommit = Thread.currentThread() === target && committed
            if (!fired && targetCommit && matches(target.stackTrace)) {
                fired = true
                action()
            }
        }
    }
    return metrics.locally(observer, block)
}

private fun bothAbortPrecommitFailures() {
    val source = byteArrayOf(3, 4, 5)
    val original = IllegalStateException("synthetic committed group cleanup")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val (thrown, interruptions) = heldSoloDeliveryFault(false, original) {
            ledger.deliver(deliveryKey(legacy, true), captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            }
        }
        (thrown === original) shouldBe true
        interruptions shouldBe 2
        val transfer = thrown.suppressed.single()
        val close = transfer.suppressed.single()
        (transfer is InterruptedException) shouldBe true
        (close is InterruptedException) shouldBe true
        (transfer !== close) shouldBe true
        transfer.stackTrace.any { it.methodName == "awaitFree" } shouldBe true
        close.stackTrace.any { it.methodName == "awaitFree" } shouldBe true
        source.any { it != 0.toByte() } shouldBe true
        val attempts = Shared(0)
        val later = Shared(0)
        val cleanup = IllegalStateException("synthetic other pending action")
        ledger.owe {
            if (attempts.modify { it + 1 to it + 1 } == 1) {
                ledger.owe { later.update { it + 1 } }
                throw cleanup
            }
        }
        shouldThrow<IllegalStateException>({ ledger.drain() }) { (it === cleanup) shouldBe true }
        source.all { it == 0.toByte() } shouldBe true
        attempts.peek() shouldBe 1
        later.peek() shouldBe 0
        ledger.drain()
        attempts.peek() shouldBe 2
        later.peek() shouldBe 1
        ledger.drain()
    }
}

private fun registrationPrecommitFault(qualified: Boolean) {
    val source = byteArrayOf(3, 4, 5)
    val marker = IllegalStateException("synthetic registration gate")
    withLoadGroups(listOf(source)) { ledger, flights, legacy, captured ->
        val (thrown, interruptions) = heldSoloDeliveryFault(true, marker) {
            ledger.deliver(deliveryKey(legacy, qualified), captured, groupDeadline, flights) {
                Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
            }
        }
        (thrown is InterruptedException) shouldBe true
        thrown.stackTrace.any { it.methodName == "awaitFree" } shouldBe true
        thrown.stackTrace.any { it.methodName == "registerPrepared" } shouldBe true
        interruptions shouldBe 1
        source.all { it == 0.toByte() } shouldBe true
        ledger.drain()
    }
}

private fun heldSoloDeliveryFault(
    registration: Boolean,
    original: IllegalStateException,
    invoke: () -> Unit,
): Pair<Exception, Int> {
    val fixture = SoloDeliveryFixture(registration, original)
    val blockerClock = TestClock(Instant.EPOCH)
    val startClock = TestClock(Instant.EPOCH)
    val holderReady = Promise<Nothing, Thread>()
    return clock.locally(SystemClock) {
        timeout(10.seconds) {
            scope {
                val blocker = async { blockerClock.sleep(1.seconds) }
                val holder = async {
                    holderReady.succeed(Thread.currentThread())
                    startClock.sleep(1.seconds)
                    try {
                        transact(soloAfter = 0) { blocker.await() }
                    } catch (_: InterruptedException) {
                        Unit
                    }
                }
                resourceScope {
                    onRelease { _ -> blocker.cancel() }
                    val holderThread = holderReady.awaitValue()
                    eventually(5.seconds) { blockerClock.sleepers() shouldBe 1 }
                    eventually(5.seconds) { startClock.sleepers() shouldBe 1 }
                    val target = async { fixture.run(invoke) }
                    fixture.prepared.awaitValue()
                    eventually(5.seconds) { fixture.pause.sleepers() shouldBe 1 }
                    startClock.adjust(1.seconds)
                    eventually(5.seconds) { (LockSupport.getBlocker(holderThread) === blocker) shouldBe true }
                    fixture.pause.adjustWhenBlocked(1.seconds)
                    val thrown = if (registration) {
                        eventually(5.seconds) { fixture.interruptGate.sleepers() shouldBe 1 }
                        blockerClock.adjust(1.seconds)
                        holder.await()
                        fixture.interruptGate.adjust(1.seconds)
                        target.await()
                    } else {
                        val failure = target.await()
                        blockerClock.adjust(1.seconds)
                        holder.await()
                        failure
                    }
                    thrown to fixture.interruptions
                }
            }
        }
    }
}

@OptIn(TurnSeam::class)
private class SoloDeliveryFixture(private val registration: Boolean, private val original: IllegalStateException) {
    val prepared = Promise<Nothing, Unit>()
    val pause = TestClock(Instant.EPOCH)
    val interruptGate = TestClock(Instant.EPOCH)
    var interruptions = 0
        private set

    fun run(invoke: () -> Unit): Exception {
        val current = Thread.currentThread()
        try {
            takingTurns(turns(current)) {
                atDeliveryCommit({ stack ->
                    if (registration) stack.any { it.className.endsWith(".Promise") && it.methodName == "await" }
                    else stack.any { it.methodName == "releaseGroup" } &&
                        stack.none { it.className.endsWith("OwnedKeyMaterial") }
                }, ::pauseAtPrepared, invoke)
            }
            throw DeliveryProbeSetupFailure()
        } catch (failure: InterruptedException) {
            if (!registration) throw failure
            return failure
        } catch (failure: IllegalStateException) {
            if (registration || failure !== original) throw failure
            return failure
        }
    }

    private fun pauseAtPrepared() {
        prepared.succeed(Unit)
        pause.sleep(1.seconds)
        if (!registration) throw original
    }

    private fun turns(current: Thread): Turns = object : Turns {
        private var insideGate = false
        override fun forking(): Any = this
        override fun started(ticket: Any) = Unit
        override fun ended() = Unit
        override fun woke() = Unit
        override fun waking(thread: Thread) = Unit
        override fun parking(): Boolean {
            if (Thread.currentThread() !== current || insideGate) return true
            val stack = current.stackTrace
            if (stack.none { it.className.endsWith(".Solo") && it.methodName == "awaitFree" }) return true
            val wanted = if (registration) "registerPrepared" else "abortPrepared"
            if (interruptions >= (if (registration) 1 else 2) || stack.none { it.methodName == wanted }) {
                throw DeliveryProbeSetupFailure()
            }
            interruptions++
            if (registration) {
                insideGate = true
                try {
                    interruptGate.sleep(1.seconds)
                } finally {
                    insideGate = false
                }
            }
            current.interrupt()
            return false
        }
    }
}

private fun deliveryRecursionRefuses() {
    val source = byteArrayOf(3, 4, 5)
    val nestedLoads = Shared(0)
    withLoadGroups(listOf(source)) { ledger, flights, key, captured ->
        clock.locally(SystemClock) {
            timeout(2.seconds) {
                val answer = ledger.deliver(key, captured, groupDeadline, flights) {
                    shouldThrow<IllegalStateException>({
                        ledger.deliver(key, captured, groupDeadline, flights) {
                            nestedLoads.update { it + 1 }
                            Either.Left(KeyError.Unavailable("synthetic recursive supplier"))
                        }
                    }) { it.message shouldBe "recursive key load" }
                    Either.Right(DataKey(KeyOwner.Platform, KeyPurpose.Enc, 1, source))
                }
                val delivery = (answer as Either.Right).value
                resourceScope {
                    onRelease { _ -> discardDelivery(delivery) }
                    nestedLoads.peek() shouldBe 0
                    source.all { it == 0.toByte() } shouldBe true
                    deliveryBytes(delivery) shouldBe listOf<Byte>(3, 4, 5)
                }
            }
        }
    }
}

private fun callerFailure(primary: Throwable, expectedExit: ExitCase) {
    val callers = KeyCallers()
    val first = IllegalStateException("synthetic outer release")
    val second = IllegalStateException("synthetic inner release")
    val released = Shared(emptyList<Pair<Int, ExitCase>>())
    shouldThrow<Throwable>({
        callers.call {
            resourceScope {
                onRelease { exit ->
                    released.update { it + (1 to exit) }
                    throw first
                }
                onRelease { exit ->
                    released.update { it + (2 to exit) }
                    throw second
                }
                throw primary
            }
        }
    }) {
        (it === primary) shouldBe true
        it.suppressed.toList() shouldBe listOf(second, first)
    }
    released.peek() shouldBe listOf(2 to expectedExit, 1 to expectedExit)
}

private fun callerGuard(mode: String) {
    val callers = KeyCallers()
    val body = HandoffDecision<Unit>()
    val commits = Shared(0)
    val invoke = {
        atDeliveryCommit({ stack -> stack.any { it.methodName == "admit" } }, {
            commits.update { it + 1 }
        }) {
            shouldThrow<IllegalStateException>({ callers.call { body.tryComplete(Unit) } }) {
                it.message shouldBe "the operation cannot run inside a transaction"
            }
        }
    }
    when (mode) {
        "ordinary" -> transact { invoke() }
        "nested" -> transact { transact { invoke() } }
        "solo" -> transact(soloAfter = 0) { invoke() }
        else -> throw DeliveryProbeSetupFailure()
    }
    body.peek() shouldBe null
    commits.peek() shouldBe 0
    callers.call { body.tryComplete(Unit) }
    body.peek() shouldBe Unit
}

private fun withCallerFenceContention(
    probe: CallerFenceProbe,
    whileBlocked: () -> Unit,
    invoke: () -> Unit,
) {
    clock.locally(SystemClock) {
        timeout(10.seconds) {
            scope {
                val blocker = async { probe.blocker.sleep(1.seconds) }
                resourceScope {
                    onRelease { _ -> blocker.cancel() }
                    val holder = async {
                        probe.holder.succeed(Thread.currentThread())
                        probe.holderStart.sleep(1.seconds)
                        transact(soloAfter = 0) { blocker.await() }
                    }
                    val target = async {
                        probe.target.succeed(Thread.currentThread())
                        probe.targetStart.sleep(1.seconds)
                        probe.run(invoke)
                    }
                    val holderThread = probe.holder.awaitValue()
                    val targetThread = probe.target.awaitValue()
                    eventually(5.seconds) { probe.blocker.sleepers() shouldBe 1 }
                    eventually(5.seconds) { probe.holderStart.sleepers() shouldBe 1 }
                    eventually(5.seconds) { probe.targetStart.sleepers() shouldBe 1 }
                    probe.holderStart.adjust(1.seconds)
                    eventually(5.seconds) { (LockSupport.getBlocker(holderThread) === blocker) shouldBe true }
                    probe.targetStart.adjust(1.seconds)
                    eventually(5.seconds) { probe.pause.sleepers() shouldBe 1 }
                    (probe.waiting.peek() === targetThread) shouldBe true
                    whileBlocked()
                    probe.blocker.adjust(1.seconds)
                    holder.await()
                    probe.pause.adjust(1.seconds)
                    target.await()
                }
            }
        }
    }
}

@OptIn(TurnSeam::class)
private class CallerFenceProbe {
    val holder = Promise<Nothing, Thread>()
    val target = Promise<Nothing, Thread>()
    val blocker = TestClock(Instant.EPOCH)
    val holderStart = TestClock(Instant.EPOCH)
    val targetStart = TestClock(Instant.EPOCH)
    val pause = TestClock(Instant.EPOCH)
    val waiting = HandoffDecision<Thread>()

    fun run(invoke: () -> Unit) {
        val current = Thread.currentThread()
        val turns = object : Turns {
            override fun forking(): Any = this
            override fun started(ticket: Any) = Unit
            override fun ended() = Unit
            override fun woke() = Unit
            override fun waking(thread: Thread) = Unit
            override fun parking(): Boolean {
                if (Thread.currentThread() !== current) return true
                val stack = current.stackTrace
                val nearest = stack.firstOrNull {
                    (it.className == "dev.surefold.Solo" && it.methodName == "awaitFree") ||
                        (it.className == "dev.surefold.TestClock" && it.methodName == "awaitUntil")
                }
                if (nearest?.className != "dev.surefold.Solo") return true
                if (stack.none {
                        it.className == "dev.surefold.Transaction" && it.methodName == "commit\$dev_surefold_surefold"
                    } ||
                    !waiting.tryComplete(current)
                ) throw CallerFenceProbeSetupFailure()
                // The nested clock park passes through this seam without selecting the outer Solo frame again.
                pause.sleep(1.seconds)
                current.interrupt()
                return false
            }
        }
        takingTurns(turns, invoke)
    }
}

private class CallerFenceProbeSetupFailure : IllegalStateException("synthetic caller fence probe setup failed")
