package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.Clock
import io.github.matthewjones372.surefold.HandoffDecision
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.SingleFlight
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.flatMap
import io.github.matthewjones372.surefold.fail.map
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.resource
import io.github.matthewjones372.surefold.resourceScope
import io.github.matthewjones372.surefold.surefoldLocal
import io.github.matthewjones372.surefold.use
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Unwrapped data keys in process memory, so a request does not wait on the KMS. A key is unwrapped again once it is
 * [soft] old; while the KMS is unavailable it is still used until it is [hard] old, and never after. Past [tenants]
 * owners, the least recently used owner's keys go. However many threads ask for one key, one unwraps it, through
 * `SingleFlight`. A key that goes is zeroed; each caller is answered a copy of its own, so a key evicted under a caller
 * is never zeroed under it.
 */
class KeyCache(
    val soft: Duration = 15.minutes,
    val hard: Duration = 60.minutes,
    val tenants: Int = 1_000,
) {
    init {
        require(soft <= hard) { "a key is refreshed after $soft, so it cannot be refused sooner, at $hard" }
        require(tenants >= 1) { "the cache holds at least one owner's keys, not $tenants" }
    }

    /** One key: an owner's [purpose] at [version], or its active one when [version] is null. */
    internal data class Slot(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int?)

    private class Held(val key: DataKey, val at: Instant)

    private val held = ConcurrentHashMap<Slot, Held>()
    private val flights = SingleFlight<Slot, KeyError, DataKey>("surefold.tenancy.crypto.keys")
    private val recent = LinkedHashMap<KeyOwner, Unit>(16, 0.75f, true)

    /** The key in [slot], unwrapped by [load] when it is missing or [soft] old. */
    internal fun get(slot: Slot, load: () -> Either<KeyError, DataKey>): Either<KeyError, DataKey> {
        touched(slot.owner)?.let(::evict)
        val cached = held[slot]
        if (cached != null && clock.get().now() < cached.at + soft) return cached.key.copy().right()
        // The flight answers a copy no slot holds, so an eviction cannot zero it before each caller takes its own.
        return when (val shared = flights.run(slot) { unwrapped(slot, load) }) {
            is Either.Right -> shared.value.copy().right()
            is Either.Left -> shared
        }
    }

    // Under the flight for [slot]: the key again if another caller's flight refreshed it meanwhile, else [load]'s.
    private fun unwrapped(slot: Slot, load: () -> Either<KeyError, DataKey>): Either<KeyError, DataKey> {
        val now = clock.get().now()
        val cached = held[slot]
        if (cached != null && now < cached.at + soft) return cached.key.copy().right()
        return when (val loaded = load()) {
            is Either.Right -> loaded.value.copy().right().also { held[slot] = Held(loaded.value, now) }

            is Either.Left ->
                if (cached != null && loaded.value is KeyError.Unavailable && now < cached.at + hard) {
                    cached.key.copy().right()
                } else {
                    loaded.also { if (cached != null) drop(slot) }
                }
        }
    }

    private fun DataKey.copy() = DataKey(owner, purpose, version, material.copyOf())

    /** Drops every key of [owner]'s at once, zeroed: after a rotation, a retirement or a shredding. */
    fun evict(owner: KeyOwner) {
        held.keys.filter { it.owner == owner }.forEach(::drop)
        synchronized(recent) { recent.remove(owner) }
    }

    /** Marks [owner] as used, and answers the owner pushed out past [tenants], if one was. */
    private fun touched(owner: KeyOwner): KeyOwner? = synchronized(recent) {
        recent[owner] = Unit
        if (recent.size > tenants) recent.keys.first() else null
    }

    private fun drop(slot: Slot) {
        held.remove(slot)?.key?.material?.fill(0)
    }

    private operator fun Instant.plus(duration: Duration): Instant = plus(duration.toJavaDuration())
}

/** Pending releases belonging to this cache's ownership protocol. */
internal class KeyCacheLedger {
    private class ReleaseIntent(val action: () -> Unit)
    data class LoadKey(val slot: KeyCache.Slot, val identity: KeyIdentity?, val epoch: Any)
    class Receipt(val group: Any, val id: Any, val clock: Clock)
    sealed interface Delivery {
        class Copy(val key: DataKey) : Delivery
        class View(val view: KeyView) : Delivery
    }
    private enum class ReturnDecision { Delivered, Abort }
    private class Prepared(val id: Any, val decision: HandoffDecision<ReturnDecision>, val abort: ReleaseIntent)
    private sealed interface Source
    private class Legacy(val key: DataKey) : Source
    private class Qualified(val material: OwnedKeyMaterial) : Source
    private class SourceRef(val id: Any, val source: Source)
    private sealed interface Outcome
    private class Value(val result: Either<KeyError, SourceRef>) : Outcome
    private class Thrown(val failure: Throwable) : Outcome
    private data class Group(
        val key: LoadKey, val deadline: Instant, val members: Int = 0,
        val loader: Any? = null, val outcome: Outcome? = null, val releasing: Boolean = false,
    )
    private data class State(
        val pending: List<ReleaseIntent> = emptyList(),
        val prepared: List<Prepared> = emptyList(),
        val open: Map<LoadKey, Any> = emptyMap(),
        val groups: Map<Any, Group> = emptyMap(),
        val receipts: Map<Any, Any> = emptyMap(),
        val records: Map<Any, SourceRef> = emptyMap(),
    )

    private val state = Shared(State())
    private val active = surefoldLocal<Set<Any>> { emptySet() }

    fun deliver(
        key: LoadKey,
        capturedClock: Clock,
        deadline: Instant,
        flights: SingleFlight<Any, KeyError, Unit>,
        load: () -> Either<KeyError, DataKey>,
    ): Either<KeyError, Delivery> {
        val decision = HandoffDecision<ReturnDecision>()
        var row: Prepared? = null
        var raw: ByteArray? = null
        return resourceScope {
            onRelease { _ ->
                if (decision.peek() != ReturnDecision.Delivered) {
                    decision.tryComplete(ReturnDecision.Abort)
                    resourceScope {
                        onRelease { _ -> raw?.fill(0) }
                        row?.let { abortPrepared(it) }
                    }
                }
            }
            val answer = enrol(key, capturedClock, deadline).use { receipt ->
                flights.run(receipt.group) { settle(receipt, load) }.flatMap {
                    replay(checkNotNull(state.peek().groups[receipt.group]?.outcome)).flatMap { ref ->
                        when (val held = ref.source) {
                            is Legacy -> {
                                val bytes = held.key.material.copyOf()
                                raw = bytes
                                row = Prepared(Any(), decision, ReleaseIntent { bytes.fill(0) })
                                registerPrepared(checkNotNull(row))
                                Either.Right(
                                    Delivery.Copy(DataKey(held.key.owner, held.key.purpose, held.key.version, bytes)),
                                )
                            }

                            is Qualified -> held.material.borrow(capturedClock) { closeExact ->
                                row = Prepared(Any(), decision, ReleaseIntent(closeExact))
                                registerPrepared(checkNotNull(row))
                            }.map { Delivery.View(it) }
                        }
                    }
                }
            }
            if (answer is Either.Right) decision.tryComplete(ReturnDecision.Delivered)
            answer
        }
    }

    private fun registerPrepared(row: Prepared) = state.update { it.copy(prepared = it.prepared + row) }

    private fun abortPrepared(row: Prepared) = resourceScope {
        onRelease { _ ->
            val current = state.peek()
            if (current.prepared.any { it.id === row.id } || current.pending.any { it === row.abort }) {
                row.abort.action()
                acknowledge(row.abort)
            }
        }
        state.update { current ->
            if (current.prepared.none { it.id === row.id }) current
            else current.copy(
                prepared = current.prepared.filterNot { it.id === row.id }, pending = current.pending + row.abort,
            )
        }
    }

    private fun acknowledge(intent: ReleaseIntent) = state.update { current ->
        current.copy(pending = current.pending.filterNot { it === intent })
    }

    fun owe(action: () -> Unit) {
        val intent = ReleaseIntent(action)
        state.update { it.copy(pending = it.pending + intent) }
    }

    fun drain() = resourceScope {
        val snapshot = state.peek()
        val rows = snapshot.prepared.map { it to it.decision.peek() }
        rows.asReversed().forEach { (row, phase) ->
            onRelease { _ ->
                when (phase) {
                    null -> Unit

                    ReturnDecision.Delivered -> state.update { current ->
                        current.copy(prepared = current.prepared.filterNot { it.id === row.id })
                    }

                    ReturnDecision.Abort -> abortPrepared(row)
                }
            }
        }
        snapshot.pending.asReversed().forEach { intent ->
            onRelease { _ ->
                intent.action()
                acknowledge(intent)
            }
        }
    }

    fun enrol(key: LoadKey, clock: Clock, deadline: Instant): Resource<Receipt> = resource {
        val id = Any()
        val candidate = Any()
        val inherited = active.get()
        onRelease { _ -> finishReceipt(id) }
        val group = state.modify { current ->
            val token = current.open[key] ?: candidate
            check(token !in inherited) { "recursive key load" }
            val held = current.groups[token] ?: Group(key, deadline)
            current.copy(
                open = current.open + (key to token), receipts = current.receipts + (id to token),
                groups = current.groups + (token to held.copy(members = held.members + 1)),
            ) to token
        }
        Receipt(group, id, clock)
    }

    // A flight must replay the original throwable, including cancellation.
    @Suppress("TooGenericExceptionCaught")
    fun settle(receipt: Receipt, load: () -> Either<KeyError, DataKey>): Either<KeyError, Unit> =
        active.locally(active.get() + receipt.group) {
            resourceScope {
                val token = Any()
                val id = Any()
                var failed: Throwable? = null
                var transferred: DataKey? = null
                onRelease { _ -> finishLoader(receipt.group, token, failed) }
                onRelease { _ -> if (state.peek().records[id] == null) transferred?.material?.fill(0) }
                try {
                    val group = state.modify { current ->
                        val held = current.groups[receipt.group] ?: retired()
                        if (held.outcome != null) current to held
                        else {
                            check(held.loader == null) { "the key load is already running" }
                            current.copy(groups = current.groups + (receipt.group to held.copy(loader = token))) to held
                        }
                    }
                    group.outcome?.let { return@resourceScope replay(it).map { Unit } }
                    val loaded = load()
                    if (loaded is Either.Right) transferred = loaded.value
                    val result = loaded.flatMap { key ->
                        val identity = group.key.identity
                        if (identity == null) Either.Right(SourceRef(id, Legacy(key)))
                        else OwnedKeyMaterial.take(identity, key, group.deadline) {
                            state.update { it.copy(records = it.records - id) }
                        }.map { SourceRef(id, Qualified(it)) }
                    }
                    state.update { current ->
                        val held = checkNotNull(current.groups[receipt.group])
                        val source = result.getOrNull()
                        current.copy(
                            open = current.open.filterValues { it !== receipt.group },
                            groups = current.groups + (receipt.group to held.copy(outcome = Value(result))),
                            records = if (source == null) current.records else current.records + (id to source),
                        )
                    }
                    result.map { Unit }
                } catch (failure: Throwable) {
                    failed = failure
                    throw failure
                }
            }
        }

    private fun finishReceipt(id: Any) = resourceScope {
        val group = state.peek().receipts[id] ?: return@resourceScope
        val intent = ReleaseIntent { releaseGroup(group) }
        onRelease { _ -> drain() }
        state.update { current ->
            val held = current.groups[group] ?: return@update current
            if (current.receipts[id] !== group) return@update current
            collect(
                current.copy(
                    receipts = current.receipts - id,
                    groups = current.groups + (group to held.copy(members = held.members - 1)),
                ),
                group, intent,
            )
        }
    }

    private fun finishLoader(id: Any, token: Any, failure: Throwable?) = resourceScope {
        val intent = ReleaseIntent { releaseGroup(id) }
        onRelease { _ -> drain() }
        state.update { current ->
            val held = current.groups[id] ?: return@update current
            val unclaimedFailure = held.loader == null && held.outcome == null && failure != null
            if (held.loader !== token && !unclaimedFailure) {
                return@update current
            }
            val outcome = held.outcome ?: Thrown(checkNotNull(failure))
            collect(
                current.copy(
                    open = current.open.filterValues { it !== id },
                    groups = current.groups + (id to held.copy(loader = null, outcome = outcome)),
                ),
                id, intent,
            )
        }
    }

    private fun collect(current: State, id: Any, intent: ReleaseIntent): State {
        val group = checkNotNull(current.groups[id])
        if (group.members != 0 || group.loader != null || group.releasing) return current
        if ((group.outcome as? Value)?.result?.getOrNull() == null) {
            return current.copy(open = current.open.filterValues { it !== id }, groups = current.groups - id)
        }
        return current.copy(
            groups = current.groups + (id to group.copy(releasing = true)), pending = current.pending + intent,
        )
    }

    private fun releaseGroup(id: Any) {
        val ref = ((state.peek().groups[id]?.outcome as? Value)?.result?.getOrNull()) ?: return
        when (val source = ref.source) {
            is Legacy -> source.key.material.fill(0)
            is Qualified -> source.material.forget()
        }
        state.update { current ->
            current.copy(
                groups = current.groups - id,
                records = if (ref.source is Legacy) current.records - ref.id else current.records,
            )
        }
    }

    private fun replay(outcome: Outcome): Either<KeyError, SourceRef> = when (outcome) {
        is Value -> outcome.result
        is Thrown -> throw outcome.failure
    }

    private fun retired(): Nothing = throw CancellationException("the key load is no longer admitted")
}
