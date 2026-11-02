package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import io.github.matthewjones372.surefold.tenancy.currentTenant
import java.sql.Connection
import java.time.ZoneOffset
import kotlin.time.Duration

/** Where sealing and blind indexes get an owner's keys: [TenantKeys], or a ring of fixed keys in a test. */
interface KeyRing {

    /** The active key of [purpose] for [owner]. */
    fun current(owner: KeyOwner, purpose: KeyPurpose): Either<KeyError, DataKey>

    /** Version [version] of [owner]'s [purpose], active or not. */
    fun version(owner: KeyOwner, purpose: KeyPurpose, version: Int): Either<KeyError, DataKey>

    /** Every version of [owner]'s [purpose] not yet retired, newest first. */
    fun versions(owner: KeyOwner, purpose: KeyPurpose): Either<KeyError, List<Int>>
}

/** An unwrapped data key: [material] is for this process only, and never stored or logged. */
class DataKey(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int, val material: ByteArray) {

    override fun toString(): String = "DataKey($owner, ${purpose.label}, v$version)"
}

/**
 * Every owner's data keys, in `surefold_tenant_keys`, each wrapped under that owner's root key in [kms]. A tenant's
 * rows are read and written in that tenant's own transaction, under the table's row-level security policy, and the
 * platform's in a system transaction. Two of the same owner's writes are taken one at a time. Keys are read through
 * [cache]; a rotation or retirement evicts the owner here and notifies [CHANNEL] as it commits, so that every other
 * process listening on it can call [evictNotified].
 */
class TenantKeys(
    private val tenants: TenantTransactions,
    private val kms: Kms,
    private val cache: KeyCache = KeyCache(),
) : KeyRing {

    /** A root key for [owner] and version 1 of each purpose; nothing, for an owner already provisioned. */
    fun provision(owner: KeyOwner): Either<KeyError, Unit> = writing(owner) { connection ->
        if (versions(connection, owner).isEmpty()) {
            val root = kms.createRoot(owner).held()
            KeyPurpose.entries.forEach { purpose -> insert(connection, owner, purpose, 1, root) }
        }
    }

    /** The active key of [purpose] for the bound tenant. */
    fun current(purpose: KeyPurpose): Either<KeyError, DataKey> =
        bound()?.let { current(it, purpose) } ?: KeyError.NoTenant.left()

    /** The active key of [purpose] for [owner], which new values are sealed under. */
    override fun current(owner: KeyOwner, purpose: KeyPurpose): Either<KeyError, DataKey> =
        cache.get(KeyCache.Slot(owner, purpose, null)) { unwrapped(owner, purpose, "state = 'active'") }

    /** Version [version] of the bound tenant's [purpose], to open or look up what it wrote. */
    fun version(purpose: KeyPurpose, version: Int): Either<KeyError, DataKey> =
        bound()?.let { version(it, purpose, version) } ?: KeyError.NoTenant.left()

    /** Version [version] of [owner]'s [purpose], active or not. */
    override fun version(owner: KeyOwner, purpose: KeyPurpose, version: Int): Either<KeyError, DataKey> =
        cache.get(KeyCache.Slot(owner, purpose, version)) { unwrapped(owner, purpose, "version = $version") }

    override fun versions(owner: KeyOwner, purpose: KeyPurpose): Either<KeyError, List<Int>> = within(owner) {
        it.query(
            "select version from surefold_tenant_keys where tenant_id = ? and purpose = '${purpose.label}' " +
                "order by version desc",
            owner,
        ) { rows -> rows.getInt(1) }
    }.right()

    /** Evicts the owner a [CHANNEL] notification names: another process rotated, retired or shredded its keys. */
    fun evictNotified(payload: String) =
        cache.evict(if (payload.isEmpty()) KeyOwner.Platform else KeyOwner.Tenant(TenantId(payload)))

    /** Version n+1 of each of [owner]'s purposes, now active; the versions before it stay, to open what they wrote. */
    fun rotate(owner: KeyOwner): Either<KeyError, Unit> = writing(owner) { connection ->
        val newest = versions(connection, owner)
        if (newest.isEmpty()) throw Refused(KeyError.Invalid("$owner has no keys to rotate: provision it first"))
        val root = connection.query("select root from surefold_tenant_keys where tenant_id = ? limit 1", owner) {
            it.getString(1)
        }.single()
        connection.update("update surefold_tenant_keys set state = 'decrypt_only' where tenant_id = ?", owner)
        newest.forEach { (purpose, version) -> insert(connection, owner, purpose, version + 1, RootKey(root)) }
        changed(connection, owner)
    }

    /** Deletes [owner]'s [version] of [purpose], once no row refers to it; the active version is never retired. */
    fun retire(owner: KeyOwner, purpose: KeyPurpose, version: Int): Either<KeyError, Unit> = writing(owner) {
        val sql = "delete from surefold_tenant_keys " +
            "where tenant_id = ? and purpose = '${purpose.label}' and version = $version and state = 'decrypt_only'"
        if (it.update(sql, owner) == 0) {
            throw Refused(KeyError.Invalid("$owner's ${purpose.label} v$version is active, or not there to retire"))
        }
        changed(it, owner)
    }

    /**
     * Deletes every one of [owner]'s key rows and schedules each root they name for deletion after [waiting], in one
     * transaction: a KMS that refuses leaves the rows. Once KMS deletes the root, every wrapped key in every copy of
     * the database, backups included, is unreadable, and so is everything sealed under them.
     */
    fun shred(owner: KeyOwner, waiting: Duration): Either<KeyError, Unit> = writing(owner) { connection ->
        val roots = connection.query("select distinct root from surefold_tenant_keys where tenant_id = ?", owner) {
            RootKey(it.getString(1))
        }
        connection.update("delete from surefold_tenant_keys where tenant_id = ?", owner)
        roots.forEach { kms.scheduleDeletion(it, waiting).held() }
        changed(connection, owner)
    }

    /** Tells every process listening on [CHANNEL] that [owner]'s keys changed, once [connection] commits. */
    private fun changed(connection: Connection, owner: KeyOwner) {
        connection.query("select pg_notify('$CHANNEL', ?)", owner) { }
    }

    private fun bound(): KeyOwner? = currentTenant()?.let(KeyOwner::Tenant)

    private fun unwrapped(owner: KeyOwner, purpose: KeyPurpose, which: String): Either<KeyError, DataKey> {
        val found = within(owner) { connection ->
            connection.query(
                "select version, wrapped, root from surefold_tenant_keys " +
                    "where tenant_id = ? and purpose = '${purpose.label}' and $which",
                owner,
            ) { Triple(it.getInt(1), it.getBytes(2), RootKey(it.getString(3))) }.singleOrNull()
        } ?: return KeyError.Invalid("$owner has no ${purpose.label} key where $which").left()
        val (version, wrapped, root) = found
        return when (val opened = kms.decrypt(root, wrapped, KeyContext(owner, purpose, version))) {
            is Either.Left -> opened
            is Either.Right -> DataKey(owner, purpose, version, opened.value).right()
        }
    }

    /**
     * [block] in [owner]'s transaction, after any other writer of [owner]'s keys; a KMS refusal rolls it back. [owner]
     * is evicted here once it ends, so a read in between cannot keep what it replaced.
     */
    private fun writing(owner: KeyOwner, block: (Connection) -> Unit): Either<KeyError, Unit> = try {
        within(owner) { connection ->
            connection.query("select pg_advisory_xact_lock(hashtext('surefold_tenant_keys:' || ?))", owner) { }
            block(connection)
        }.right()
    } catch (refused: Refused) {
        refused.error.left()
    }.also { cache.evict(owner) }

    private fun <A> within(owner: KeyOwner, block: (Connection) -> A): A = when (owner) {
        is KeyOwner.Tenant -> tenants.transaction(owner.id, block)
        KeyOwner.Platform -> tenants.system(SYSTEM_ACTOR, "the platform's own keys", block)
    }

    /** The newest version of each of [owner]'s purposes, none for an owner not provisioned. */
    private fun versions(connection: Connection, owner: KeyOwner): Map<KeyPurpose, Int> = connection.query(
        "select purpose, max(version) from surefold_tenant_keys where tenant_id = ? group by purpose",
        owner,
    ) { rows -> KeyPurpose.entries.single { it.label == rows.getString(1) } to rows.getInt(2) }.toMap()

    private fun insert(connection: Connection, owner: KeyOwner, purpose: KeyPurpose, version: Int, root: RootKey) {
        val key = kms.generateDataKey(root, KeyContext(owner, purpose, version)).held()
        connection.prepareStatement(INSERT).use { statement ->
            listOf(column(owner), purpose.label, version, key.wrapped, root.id, now())
                .forEachIndexed { at, value -> statement.setObject(at + 1, value) }
            statement.executeUpdate()
        }
        key.plaintext.fill(0)
    }

    private fun now() = clock.get().now().atOffset(ZoneOffset.UTC)

    private fun <A> Either<KeyError, A>.held(): A = when (this) {
        is Either.Left -> throw Refused(value)
        is Either.Right -> value
    }

    /** A KMS refusal, thrown out of a transaction so that it rolls back, and answered as the value it carries. */
    private class Refused(val error: KeyError) : RuntimeException(error.toString(), null, false, false)

    companion object {
        /** What a change to an owner's keys is notified on, with the owner's `tenant_id` as the payload. */
        const val CHANNEL = "surefold_tenant_keys"

        private const val SYSTEM_ACTOR = "surefold-tenancy-crypto"
        private const val INSERT = "insert into surefold_tenant_keys " +
            "(tenant_id, purpose, version, wrapped, root, state, created_at) values (?, ?, ?, ?, ?, 'active', ?)"
    }
}

/**
 * The `tenant_id` an owner's rows carry, and its [TenantKeys.CHANNEL] payload: the tenant's id, or `''` for the
 * platform, which no tenant id can be.
 */
private fun column(owner: KeyOwner): String = when (owner) {
    is KeyOwner.Tenant -> owner.id.value
    KeyOwner.Platform -> ""
}

private fun <A> Connection.query(sql: String, owner: KeyOwner, row: (java.sql.ResultSet) -> A): List<A> =
    prepareStatement(sql).use { statement ->
        statement.setString(1, column(owner))
        statement.executeQuery().use { rows -> generateSequence { if (rows.next()) row(rows) else null }.toList() }
    }

private fun Connection.update(sql: String, owner: KeyOwner): Int = prepareStatement(sql).use { statement ->
    statement.setString(1, column(owner))
    statement.executeUpdate()
}
