package io.github.matthewjones372.surefold.tenancy.crypto

import io.github.matthewjones372.surefold.Clock
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.resourceScope
import java.time.Instant

/** One transferred source array, retained independently of its views and admitted uses. */
internal class OwnedKeyMaterial private constructor(
    private val identity: KeyIdentity,
    private val source: ByteArray,
    private val hardDeadline: Instant,
    private val onTerminal: () -> Unit,
) {
    private data class State(
        val retained: Boolean = true,
        val revoked: Boolean = false,
        val zeroed: Boolean = false,
        val notified: Boolean = false,
        val views: Set<Any> = emptySet(),
        val uses: Map<Any, ByteArray?> = emptyMap(),
    )

    private val state = Shared(State())

    fun borrow(clock: Clock): Either<KeyError, KeyView> = borrow(clock) {}

    fun borrow(
        clock: Clock,
        beforeAdmission: (closeExact: () -> Unit) -> Unit,
    ): Either<KeyError, KeyView> = resourceScope {
        refused(clock)?.let { return@resourceScope Either.Left(it) }
        val id = Any()
        val closeExact = { change { it.copy(views = it.views - id) } }
        var handedOver = false
        onRelease { _ ->
            if (!handedOver) closeExact()
        }
        beforeAdmission(closeExact)
        val admitted = modify {
            if (it.revoked || it.zeroed) it to false else it.copy(views = it.views + id) to true
        }
        if (!admitted) return@resourceScope Either.Left(denied)
        val view = object : KeyView {
            override val identity: KeyIdentity = this@OwnedKeyMaterial.identity
            override fun <A> using(block: (ByteArray) -> A): Either<KeyError, A> = use(id, clock, block)
            override fun close() = closeExact()
        }
        handedOver = true
        Either.Right(view)
    }

    fun forget() = change { it.copy(retained = false) }

    fun revoke() = change { it.copy(retained = false, revoked = true) }

    private fun <A> use(id: Any, clock: Clock, block: (ByteArray) -> A): Either<KeyError, A> = resourceScope {
        refused(clock, id)?.let { return@resourceScope Either.Left(it) }
        val token = Any()
        var working: ByteArray? = null
        onRelease { _ ->
            working?.fill(0)
            change { it.copy(uses = it.uses - token) }
        }
        val admitted = modify {
            if (it.revoked || it.zeroed || id !in it.views) it to false
            else it.copy(uses = it.uses + (token to null)) to true
        }
        if (!admitted) return@resourceScope Either.Left(denied)
        val copy = source.copyOf()
        working = copy
        val published = modify {
            if (it.revoked) it to false else it.copy(uses = it.uses + (token to copy)) to true
        }
        if (!published) return@resourceScope Either.Left(denied)
        refused(clock)?.let { return@resourceScope Either.Left(it) }
        val answer = block(copy)
        refused(clock)?.let { return@resourceScope Either.Left(it) }
        Either.Right(answer)
    }

    private fun refused(clock: Clock, view: Any? = null): KeyError? {
        val current = state.peek()
        val closed = view != null && view !in current.views
        if (current.revoked || current.zeroed || closed) return denied
        if (clock.now().isBefore(hardDeadline)) return null
        forget()
        return KeyError.Unavailable("the key view has expired")
    }

    private fun change(update: (State) -> State) = modify { update(it) to Unit }

    private fun <A> modify(update: (State) -> Pair<State, A>): A = resourceScope {
        onRelease { _ -> releaseTerminal() }
        state.modify {
            val (next, answer) = update(it)
            next.copy(
                zeroed = next.zeroed ||
                    (!next.retained && next.views.isEmpty() && next.uses.isEmpty()),
            ) to answer
        }
    }

    private fun releaseTerminal() {
        val current = state.peek()
        if (current.zeroed || current.revoked) source.fill(0)
        if (current.revoked) current.uses.values.forEach { it?.fill(0) }
        val terminal = current.zeroed || (current.revoked && current.uses.isEmpty())
        if (terminal && !current.notified) {
            onTerminal()
            state.update { it.copy(notified = true) }
        }
    }

    companion object {
        private val denied = KeyError.Denied("the key view is unavailable")

        fun take(
            identity: KeyIdentity,
            key: DataKey,
            hardDeadline: Instant,
            onTerminal: () -> Unit = {},
        ): Either<KeyError, OwnedKeyMaterial> {
            if (identity.owner != key.owner || identity.purpose != key.purpose || identity.version != key.version) {
                key.material.fill(0)
                return Either.Left(KeyError.Invalid("the key metadata does not match"))
            }
            return Either.Right(OwnedKeyMaterial(identity, key.material, hardDeadline, onTerminal))
        }
    }
}
