package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.Counter
import io.github.matthewjones372.surefold.Metrics
import io.github.matthewjones372.surefold.NoMetrics
import io.github.matthewjones372.surefold.Promise
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.SystemClock
import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.awaitValue
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.metrics
import io.github.matthewjones372.surefold.resourceScope
import io.github.matthewjones372.surefold.scope
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.timeout
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

object KeyViewSuite : Suite() {
    init {
        test("identity equality owns the revision and compares its contents") {
            val revision = byteArrayOf(1, 2, 3)
            val identity = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, revision)
            val equal = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(1, 2, 3))
            val hash = identity.hashCode()

            revision.fill(0)

            identity shouldBe equal
            identity.hashCode() shouldBe hash
            identity.hashCode() shouldBe equal.hashCode()
        }

        test("every identity dimension distinguishes retained material") {
            val identity = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(1))
            val different = listOf(
                KeyIdentity(KeyOwner.Platform, KeyPurpose.Enc, 1, byteArrayOf(1)),
                KeyIdentity(viewOwner, KeyPurpose.Bidx, 1, byteArrayOf(1)),
                KeyIdentity(viewOwner, KeyPurpose.Enc, 2, byteArrayOf(1)),
                KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(2)),
            )

            different.all { it != identity } shouldBe true
        }

        test("identity rejects empty revisions and nonpositive versions") {
            shouldThrow<IllegalArgumentException> {
                KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf())
            }
            listOf(0, -1).forEach { version ->
                shouldThrow<IllegalArgumentException>({
                    KeyIdentity(viewOwner, KeyPurpose.Enc, version, byteArrayOf(1))
                }) { it.message shouldBe "invalid key identity" }
            }
        }

        test("identity text is fixed and reveals none of its fields") {
            val first = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(1, 2, 3))
            val second = KeyIdentity(KeyOwner.Platform, KeyPurpose.Bidx, 71, byteArrayOf(94))

            first.toString() shouldBe second.toString()
            first.toString().contains("synthetic-view-owner") shouldBe false
        }

        test("normal use returns its answer and wipes its private working copy") {
            val source = byteArrayOf(3, 4, 5)
            val seen = Shared<ByteArray?>(null)
            resourceScope {
                onRelease { source.fill(0) }
                val identity = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(1))
                val material = OwnedKeyMaterial.take(
                    identity, DataKey(viewOwner, KeyPurpose.Enc, 1, source), viewDeadline,
                ).required()
                onRelease { material.revoke() }
                val view = material.borrow(TestClock(Instant.EPOCH)).required()
                onRelease { view.close() }

                view.identity shouldBe identity
                view.using { working ->
                    seen.update { working }
                    (working !== source && working.contentEquals(source))
                } shouldBe Either.Right(true)

                checkNotNull(seen.peek()).all { it == 0.toByte() } shouldBe true
                source.any { it != 0.toByte() } shouldBe true
                material.forget()
                source.any { it != 0.toByte() } shouldBe true
                view.close()
                source.all { it == 0.toByte() } shouldBe true
            }
            source.all { it == 0.toByte() } shouldBe true
        }

        test("a held view refuses expiry before entering its callback") {
            val clock = TestClock(Instant.EPOCH)
            withView(clock) { _, view, _ ->
                val entered = Shared(false)
                clock.adjust(10.seconds)

                view.using { entered.update { true } } shouldBe expiredView
                entered.peek() shouldBe false
            }
        }

        test("normal callback crossing the original deadline is refused and wiped") {
            val clock = TestClock(Instant.EPOCH)
            val seen = Shared<ByteArray?>(null)
            withView(clock) { _, view, _ ->
                view.using { working ->
                    seen.update { working }
                    clock.adjust(10.seconds)
                    "synthetic answer"
                } shouldBe expiredView

                checkNotNull(seen.peek()).all { it == 0.toByte() } shouldBe true
            }
        }

        test("revocation during an admitted callback refuses its normal answer") {
            val clock = TestClock(Instant.EPOCH)
            withView(clock) { material, view, source ->
                view.using { working ->
                    material.revoke()
                    source.all { it == 0.toByte() } shouldBe true
                    working.all { it == 0.toByte() } shouldBe true
                    "synthetic answer"
                } shouldBe deniedView
            }
        }

        test("revoked forgotten views cannot enter and cannot be borrowed again") {
            val clock = TestClock(Instant.EPOCH)
            withView(clock) { material, view, _ ->
                val entered = Shared(false)
                material.forget()
                material.revoke()

                view.using { entered.update { true } } shouldBe deniedView
                material.borrow(clock) shouldBe deniedView
                entered.peek() shouldBe false
            }
        }

        test("benign close permits an admitted normal answer and refuses later uses") {
            withView(TestClock(Instant.EPOCH)) { material, view, source ->
                material.forget()
                view.using { working ->
                    view.close()
                    source.any { it != 0.toByte() } shouldBe true
                    working.contentEquals(source) shouldBe true
                    "synthetic answer"
                } shouldBe Either.Right("synthetic answer")
                source.all { it == 0.toByte() } shouldBe true

                val entered = Shared(false)
                view.using { entered.update { true } } shouldBe deniedView
                entered.peek() shouldBe false
            }
        }

        test("a view keeps its captured clock when the ambient clock is rebound") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { _, view, _ ->
                clock.locally(TestClock(Instant.EPOCH.plusSeconds(100))) {
                    view.using { "synthetic answer" } shouldBe Either.Right("synthetic answer")
                }
                captured.adjust(10.seconds)
                clock.locally(TestClock(Instant.EPOCH)) {
                    view.using { "synthetic answer" } shouldBe expiredView
                }
            }
        }

        test("defects and interruption retain their identity and wipe working copies") {
            listOf(IllegalStateException("synthetic marker"), InterruptedException("synthetic interrupt"))
                .forEach { marker ->
                    val seen = Shared<ByteArray?>(null)
                    withView(TestClock(Instant.EPOCH)) { material, view, _ ->
                        shouldThrow<Exception>({
                            view.using { working ->
                                seen.update { working }
                                material.revoke()
                                throw marker
                            }
                        }) { (it === marker) shouldBe true }
                        checkNotNull(seen.peek()).all { it == 0.toByte() } shouldBe true
                    }
                }
        }

        test("terminal forgotten material cannot be borrowed or resurrected") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { material, view, source ->
                material.forget()
                view.close()
                material.forget()
                view.close()

                source.all { it == 0.toByte() } shouldBe true
                material.borrow(captured) shouldBe deniedView
                material.revoke()
                material.revoke()
            }
        }

        test("concurrent uses own distinct arrays while benign close waits for their release") {
            concurrentViews(revoke = false)
        }

        test("revocation reaches all concurrently admitted working copies") {
            concurrentViews(revoke = true)
        }

        test("early ownership registers once and closes the returned exact view") {
            earlyRegistrationOwnsExactView()
        }

        test("a failed early registration leaves no admitted view") {
            earlyRegistrationFailureAdmitsNothing()
        }

        test("a committed early registration keeps its capability when its observer throws") {
            earlyRegistrationObserverRetainsCapability()
        }

        test("early ownership is recorded before admission instrumentation can throw") {
            earlyOwnershipPrecedesAdmissionObserver()
        }

        test("early ownership survives admission and rollback observers throwing") {
            earlyOwnershipSurvivesRollbackObserver()
        }

        test("an early capability closes only its independently held view") {
            earlyCapabilityClosesOnlyItsView()
        }

        test("expired and revoked material refuses before early registration") {
            earlyRefusalSkipsRegistration()
        }

        test("an early capability retries a failed terminal notice without repeating its acknowledgement") {
            earlyCapabilityRetriesTerminalNotice()
        }

        test("a committed borrow with throwing instrumentation releases its unpublished view") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { material, view, source ->
                view.close()
                val marker = IllegalStateException("synthetic instrumentation")
                failingCommit(marker) {
                    shouldThrow<IllegalStateException>({ material.borrow(captured) }) {
                        (it === marker) shouldBe true
                    }
                }
                material.forget()
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("a committed use with throwing instrumentation releases its unpublished use") {
            withView(TestClock(Instant.EPOCH)) { material, view, source ->
                val entered = Shared(false)
                val marker = IllegalStateException("synthetic instrumentation")
                failingCommit(marker) {
                    shouldThrow<IllegalStateException>({ view.using { entered.update { true } } }) {
                        (it === marker) shouldBe true
                    }
                }
                entered.peek() shouldBe false
                material.forget()
                view.close()
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("borrow cleanup preserves the first instrumentation failure and suppresses the second") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { material, view, source ->
                view.close()
                val first = IllegalStateException("synthetic admission")
                val cleanup = IllegalStateException("synthetic cleanup")
                failingCommits(listOf(first, cleanup)) {
                    shouldThrow<IllegalStateException>({ material.borrow(captured) }) {
                        (it === first) shouldBe true
                        it.suppressed.any { suppressed -> suppressed === cleanup } shouldBe true
                    }
                }
                material.forget()
                source.all { it == 0.toByte() } shouldBe true
            }
        }

        test("closed view refusal takes priority over expiry") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { _, view, _ ->
                view.close()
                captured.adjust(10.seconds)

                view.using { error("a closed callback must not enter") } shouldBe deniedView
            }
        }

        test("revoked view refusal takes priority over expiry") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { material, view, _ ->
                material.revoke()
                captured.adjust(10.seconds)

                view.using { error("a revoked callback must not enter") } shouldBe deniedView
            }
        }

        test("revoked borrow refusal takes priority over expiry") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { material, _, _ ->
                material.revoke()
                captured.adjust(10.seconds)

                material.borrow(captured) shouldBe deniedView
            }
        }

        test("expiry during use admission refuses before the callback") {
            cutoffBeforeBody(commit = 0, revoke = false)
        }

        test("expiry during working-copy publication refuses before the callback") {
            cutoffBeforeBody(commit = 1, revoke = false)
        }

        test("revocation during working-copy publication refuses before the callback") {
            cutoffBeforeBody(commit = 1, revoke = true)
        }

        test("independent held views close without releasing each other's material") {
            val captured = TestClock(Instant.EPOCH)
            withView(captured) { material, first, source ->
                resourceScope {
                    val second = material.borrow(captured).required()
                    onRelease { second.close() }
                    first.close()
                    first.close()

                    second.using { it.contentEquals(source) } shouldBe Either.Right(true)
                    material.forget()
                    source.any { it != 0.toByte() } shouldBe true
                    second.using { it.contentEquals(source) } shouldBe Either.Right(true)

                    second.close()
                    source.all { it == 0.toByte() } shouldBe true
                }
            }
        }

        test("forgotten held views keep the terminal notice pending") {
            val notices = Shared(0)
            withView(TestClock(Instant.EPOCH), onTerminal = { notices.update { it + 1 } }) { material, view, source ->
                material.forget()
                notices.peek() shouldBe 0
                source.any { it != 0.toByte() } shouldBe true
                view.using { "still owned" } shouldBe Either.Right("still owned")
                notices.peek() shouldBe 0
            }
            notices.peek() shouldBe 1
        }

        test("last forgotten view notifies once after the source is wiped") {
            val notices = Shared(0)
            val seen = Shared<ByteArray?>(null)
            withView(TestClock(Instant.EPOCH), onTerminal = {
                checkNotNull(seen.peek()).all { it == 0.toByte() } shouldBe true
                notices.update { it + 1 }
            }) { material, view, source ->
                seen.update { source }
                material.forget()
                view.close()
                notices.peek() shouldBe 1
                view.close()
                material.forget()
                notices.peek() shouldBe 1
            }
            notices.peek() shouldBe 1
        }

        test("revoked idle handles notify without waiting for their close") {
            val notices = Shared(0)
            withView(TestClock(Instant.EPOCH), onTerminal = { notices.update { it + 1 } }) { material, view, source ->
                material.revoke()
                source.all { it == 0.toByte() } shouldBe true
                notices.peek() shouldBe 1
                view.using { error("a revoked view must not enter") } shouldBe deniedView
                view.close()
                material.revoke()
                notices.peek() shouldBe 1
            }
        }

        test("revoked admitted work releases before the terminal notice") {
            val notices = Shared(0)
            val seen = Shared<ByteArray?>(null)
            withView(TestClock(Instant.EPOCH), onTerminal = {
                checkNotNull(seen.peek()).all { it == 0.toByte() } shouldBe true
                notices.update { it + 1 }
            }) { material, view, _ ->
                view.using { working ->
                    seen.update { working }
                    material.revoke()
                    notices.peek() shouldBe 0
                    "revoked answer"
                } shouldBe deniedView
                notices.peek() shouldBe 1
            }
        }

        test("a terminal notice throwing after its own commit remains pending") {
            val attempts = Shared(0)
            val present = Shared(true)
            val removals = Shared(0)
            val failure = IllegalStateException("synthetic notice")
            withView(TestClock(Instant.EPOCH), onTerminal = {
                val number = metrics.locally(NoMetrics) {
                    if (present.modify { false to it }) removals.update { it + 1 }
                    attempts.modify { it + 1 to it + 1 }
                }
                if (number == 1) throw failure
            }) { material, view, source ->
                material.forget()
                shouldThrow<IllegalStateException>({ view.close() }) { (it === failure) shouldBe true }
                source.all { it == 0.toByte() } shouldBe true
                attempts.peek() shouldBe 1
                view.close()
                attempts.peek() shouldBe 2
                removals.peek() shouldBe 1
                view.close()
                attempts.peek() shouldBe 2
            }
        }

        test("a committed terminal acknowledgement survives instrumentation failure") {
            val notices = Shared(0)
            val failure = IllegalStateException("synthetic acknowledgement")
            withView(TestClock(Instant.EPOCH), onTerminal = {
                metrics.locally(NoMetrics) { notices.update { it + 1 } }
            }) { material, view, _ ->
                material.forget()
                onCommittedStep(1, { throw failure }) {
                    shouldThrow<IllegalStateException>({ view.close() }) { (it === failure) shouldBe true }
                }
                notices.peek() shouldBe 1
                view.close()
                material.forget()
                notices.peek() shouldBe 1
            }
        }

        test("a publication failure keeps terminal notice failure suppressed") {
            val attempts = Shared(0)
            val primary = IllegalStateException("synthetic publication")
            val cleanup = IllegalStateException("synthetic notice")
            withView(TestClock(Instant.EPOCH), onTerminal = {
                val number = metrics.locally(NoMetrics) { attempts.modify { it + 1 to it + 1 } }
                if (number == 1) throw cleanup
            }) { material, view, source ->
                view.close()
                onCommittedStep(0, { throw primary }) {
                    shouldThrow<IllegalStateException>({ material.forget() }) {
                        (it === primary) shouldBe true
                        it.suppressed.toList() shouldBe listOf(cleanup)
                    }
                }
                source.all { it == 0.toByte() } shouldBe true
                material.forget()
                attempts.peek() shouldBe 2
            }
        }

        test("a callback failure stays primary when its terminal notice rethrows it") {
            val attempts = Shared(0)
            val primary = IllegalStateException("synthetic callback")
            withView(TestClock(Instant.EPOCH), onTerminal = {
                val number = metrics.locally(NoMetrics) { attempts.modify { it + 1 to it + 1 } }
                if (number == 1) throw primary
            }) { material, view, _ ->
                material.forget()
                shouldThrow<IllegalStateException>({
                    view.using {
                        view.close()
                        throw primary
                    }
                }) {
                    (it === primary) shouldBe true
                    it.suppressed.toList() shouldBe emptyList()
                }
                material.forget()
                attempts.peek() shouldBe 2
            }
        }

        test("a parallel acknowledgement survives another notice attempt failing") {
            val attempts = Shared(0)
            val entered = Promise<Nothing, Unit>()
            val finish = Promise<Nothing, Unit>()
            val failure = IllegalStateException("synthetic earlier notice")
            withView(TestClock(Instant.EPOCH), onTerminal = {
                val number = metrics.locally(NoMetrics) { attempts.modify { it + 1 to it + 1 } }
                if (number == 1) {
                    entered.succeed(Unit)
                    finish.awaitValue()
                    throw failure
                }
            }) { material, view, _ ->
                material.forget()
                clock.locally(SystemClock) {
                    timeout(10.seconds) {
                        scope {
                            val first = async {
                                shouldThrow<IllegalStateException>({ view.close() }) { (it === failure) shouldBe true }
                            }
                            try {
                                entered.awaitValue()
                                view.close()
                                attempts.peek() shouldBe 2
                            } finally {
                                finish.succeed(Unit)
                            }
                            first.await()
                        }
                    }
                }
                view.close()
                attempts.peek() shouldBe 2
            }
        }

        test("borrowing at the original deadline is refused") {
            val clock = TestClock(Instant.EPOCH)
            withView(clock) { material, _, _ ->
                clock.adjust(10.seconds)

                material.borrow(clock) shouldBe expiredView
            }
        }

        test("material transferred under mismatching metadata is refused and wiped") {
            val identity = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(1))
            val mismatches = listOf(
                Triple(KeyOwner.Platform, KeyPurpose.Enc, 1),
                Triple(viewOwner, KeyPurpose.Bidx, 1),
                Triple(viewOwner, KeyPurpose.Enc, 2),
            )
            mismatches.forEach { (owner, purpose, version) ->
                val source = byteArrayOf(3, 4, 5)
                resourceScope {
                    onRelease { source.fill(0) }
                    val result = OwnedKeyMaterial.take(identity, DataKey(owner, purpose, version, source), viewDeadline)
                    if (result is Either.Right) onRelease { result.value.revoke() }

                    (result is Either.Left && result.value is KeyError.Invalid) shouldBe true
                    source.all { it == 0.toByte() } shouldBe true
                }
            }
        }
    }
}

private val viewOwner = KeyOwner.Tenant(TenantId("synthetic-view-owner"))
private val viewDeadline: Instant = Instant.EPOCH.plusSeconds(3_600)

private fun <A> Either<KeyError, A>.required(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> error("a valid synthetic key was refused")
}

private val deniedView = Either.Left(KeyError.Denied("the key view is unavailable"))
private val expiredView = Either.Left(KeyError.Unavailable("the key view has expired"))

private fun <A> withView(
    clock: TestClock,
    onTerminal: () -> Unit = {},
    block: (OwnedKeyMaterial, KeyView, ByteArray) -> A,
): A = resourceScope {
    val source = byteArrayOf(3, 4, 5)
    onRelease { source.fill(0) }
    val identity = KeyIdentity(viewOwner, KeyPurpose.Enc, 1, byteArrayOf(1))
    val material = OwnedKeyMaterial.take(
        identity, DataKey(viewOwner, KeyPurpose.Enc, 1, source), Instant.EPOCH.plusSeconds(10),
        onTerminal = onTerminal,
    ).required()
    onRelease { material.revoke() }
    val view = material.borrow(clock).required()
    onRelease { view.close() }
    block(material, view, source)
}

private fun concurrentViews(revoke: Boolean) {
    val copies = Shared<Map<Int, ByteArray>>(emptyMap())
    val entered = List(2) { Promise<Nothing, Unit>() }
    val release = Promise<Nothing, Unit>()
    withView(TestClock(Instant.EPOCH)) { material, view, source ->
        clock.locally(SystemClock) {
            timeout(10.seconds) {
                scope {
                    val calls = (0..1).map { index ->
                        async {
                            view.using { working ->
                                copies.update { it + (index to working) }
                                entered[index].succeed(Unit)
                                release.awaitValue()
                                index
                            }
                        }
                    }
                    try {
                        entered.forEach { it.awaitValue() }
                        val actual = copies.peek()
                        (actual[0] !== actual[1]) shouldBe true
                        actual.values.all { it !== source && it.contentEquals(source) } shouldBe true
                        material.forget()
                        view.close()
                        source.any { it != 0.toByte() } shouldBe true
                        if (revoke) {
                            material.revoke()
                            actual.values.all { bytes -> bytes.all { it == 0.toByte() } } shouldBe true
                        }
                    } finally {
                        release.succeed(Unit)
                    }
                    calls.forEachIndexed { index, call ->
                        call.await() shouldBe if (revoke) deniedView else Either.Right(index)
                    }
                }
            }
        }
        source.all { it == 0.toByte() } shouldBe true
        copies.peek().values.all { bytes -> bytes.all { it == 0.toByte() } } shouldBe true
    }
}

private fun <A> failingCommit(marker: IllegalStateException, block: () -> A): A =
    failingCommits(listOf(marker), block)

private fun <A> failingCommits(failures: List<IllegalStateException>, block: () -> A): A {
    val calls = Shared(0)
    val failing = object : Metrics by NoMetrics {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
            if (name == "surefold.transact.runs" && tags["outcome"] == "committed") {
                val index = metrics.locally(NoMetrics) { calls.modify { it + 1 to it } }
                if (index < failures.size) throw failures[index]
            }
        }
    }
    return metrics.locally(failing, block)
}

private fun cutoffBeforeBody(commit: Int, revoke: Boolean) {
    val captured = TestClock(Instant.EPOCH)
    val entered = Shared(false)
    withView(captured) { material, view, _ ->
        onCommittedStep(commit, {
            if (revoke) material.revoke() else captured.adjust(10.seconds)
        }) {
            view.using { entered.update { true } } shouldBe if (revoke) deniedView else expiredView
        }
        entered.peek() shouldBe false
    }
}

private fun <A> onCommittedStep(target: Int, action: () -> Unit, block: () -> A): A {
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

private fun <A> failingNamedCommits(target: String, failures: List<IllegalStateException>, block: () -> A): A {
    val calls = Shared(0, "early-fault-count")
    val failing = object : Metrics by NoMetrics {
        override fun counter(name: String, tags: Map<String, String>): Counter = Counter {
            if (name == "surefold.transact.runs" && tags["transact"] == target && tags["outcome"] == "committed") {
                val index = metrics.locally(NoMetrics) { calls.modify { it + 1 to it } }
                if (index < failures.size) throw failures[index]
            }
        }
    }
    return metrics.locally(failing, block)
}

private fun earlyRegistrationOwnsExactView() {
    val captured = TestClock(Instant.EPOCH)
    val owner = Shared<(() -> Unit)?>(null, "early-owner")
    val calls = Shared(0, "early-registration-count")
    withView(captured) { material, initial, source ->
        initial.close()
        resourceScope {
            val view = material.borrow(captured) { closeExact ->
                owner.update { closeExact }
                calls.update { it + 1 }
            }.required()
            onRelease { view.close() }
            calls.peek() shouldBe 1
            view.using { it.contentEquals(source) } shouldBe Either.Right(true)
            checkNotNull(owner.peek()).invoke()
            view.using { Unit } shouldBe deniedView
            material.forget()
            source.all { it == 0.toByte() } shouldBe true
            checkNotNull(owner.peek()).invoke()
        }
    }
}

private fun earlyRegistrationFailureAdmitsNothing() {
    val captured = TestClock(Instant.EPOCH)
    val marker = IllegalStateException("synthetic registration")
    withView(captured) { material, initial, source ->
        initial.close()
        shouldThrow<IllegalStateException>({
            material.borrow(captured) { throw marker }
        }) { (it === marker) shouldBe true }
        material.forget()
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun earlyRegistrationObserverRetainsCapability() {
    val captured = TestClock(Instant.EPOCH)
    val owner = Shared<(() -> Unit)?>(null, "early-owner")
    val marker = IllegalStateException("synthetic ownership observer")
    withView(captured) { material, initial, source ->
        initial.close()
        shouldThrow<IllegalStateException>({
            failingNamedCommits("early-owner", listOf(marker)) {
                material.borrow(captured) { closeExact -> owner.update { closeExact } }
            }
        }) { (it === marker) shouldBe true }
        val closeExact = checkNotNull(owner.peek())
        material.forget()
        source.all { it == 0.toByte() } shouldBe true
        closeExact()
        closeExact()
    }
}

private fun earlyOwnershipPrecedesAdmissionObserver() {
    val captured = TestClock(Instant.EPOCH)
    val owner = Shared<(() -> Unit)?>(null, "early-owner")
    val marker = IllegalStateException("synthetic admission observer")
    withView(captured) { material, initial, source ->
        initial.close()
        shouldThrow<IllegalStateException>({
            failingNamedCommits("unnamed", listOf(marker)) {
                material.borrow(captured) { closeExact -> owner.update { closeExact } }
            }
        }) { (it === marker) shouldBe true }
        val closeExact = checkNotNull(owner.peek())
        closeExact()
        material.forget()
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun earlyOwnershipSurvivesRollbackObserver() {
    val captured = TestClock(Instant.EPOCH)
    val owner = Shared<(() -> Unit)?>(null, "early-owner")
    val primary = IllegalStateException("synthetic admission observer")
    val cleanup = IllegalStateException("synthetic rollback observer")
    withView(captured) { material, initial, source ->
        initial.close()
        shouldThrow<IllegalStateException>({
            failingNamedCommits("unnamed", listOf(primary, cleanup)) {
                material.borrow(captured) { closeExact -> owner.update { closeExact } }
            }
        }) {
            (it === primary) shouldBe true
            it.suppressed.toList() shouldBe listOf(cleanup)
        }
        val closeExact = checkNotNull(owner.peek())
        closeExact()
        closeExact()
        material.forget()
        source.all { it == 0.toByte() } shouldBe true
    }
}

private fun earlyCapabilityClosesOnlyItsView() {
    val captured = TestClock(Instant.EPOCH)
    val owner = Shared<(() -> Unit)?>(null, "early-owner")
    withView(captured) { material, initial, source ->
        initial.close()
        resourceScope {
            val first = material.borrow(captured) { closeExact -> owner.update { closeExact } }.required()
            onRelease { first.close() }
            val second = material.borrow(captured).required()
            onRelease { second.close() }
            checkNotNull(owner.peek()).invoke()
            checkNotNull(owner.peek()).invoke()
            first.using { Unit } shouldBe deniedView
            second.using { it.contentEquals(source) } shouldBe Either.Right(true)
            material.forget()
            source.any { it != 0.toByte() } shouldBe true
            second.close()
            source.all { it == 0.toByte() } shouldBe true
        }
    }
}

private fun earlyRefusalSkipsRegistration() {
    listOf(false, true).forEach { revoked ->
        val captured = TestClock(Instant.EPOCH)
        val calls = Shared(0, "early-registration-count")
        withView(captured) { material, _, _ ->
            if (revoked) material.revoke() else captured.adjust(10.seconds)
            val answer = material.borrow(captured) { _ -> calls.update { it + 1 } }
            answer shouldBe if (revoked) deniedView else expiredView
            calls.peek() shouldBe 0
        }
    }
}

private fun earlyCapabilityRetriesTerminalNotice() {
    val captured = TestClock(Instant.EPOCH)
    val owner = Shared<(() -> Unit)?>(null, "early-owner")
    val notices = Shared(0, "early-terminal-count")
    val marker = IllegalStateException("synthetic terminal notice")
    withView(captured, onTerminal = {
        val attempt = notices.modify { it + 1 to it }
        if (attempt == 0) throw marker
    }) { material, initial, source ->
        initial.close()
        resourceScope {
            val view = material.borrow(captured) { closeExact -> owner.update { closeExact } }.required()
            onRelease { view.close() }
            val closeExact = checkNotNull(owner.peek())
            material.forget()
            shouldThrow<IllegalStateException>({ closeExact() }) { (it === marker) shouldBe true }
            source.all { it == 0.toByte() } shouldBe true
            notices.peek() shouldBe 1
            closeExact()
            notices.peek() shouldBe 2
            closeExact()
            notices.peek() shouldBe 2
            view.using { Unit } shouldBe deniedView
        }
    }
}
