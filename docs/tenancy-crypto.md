# Keys per tenant

`surefold-tenancy-crypto` gives every tenant data keys of its own, wrapped under a root key that only a KMS holds. A
backup, a snapshot shared by mistake or a restored side database holds the wrapped keys and never the root, so it
cannot open what they sealed. Delete a tenant's root and every copy of its data is unreadable, backups included.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-tenancy-crypto:0.17.0")
    implementation("dev.surefold:surefold-tenancy-crypto-aws:0.17.0") // AwsKms
}
```

## Keys

```kotlin
val kms: Kms = AwsKms(AwsKms.client(), aliasPrefix = "alias/bookings/prod/tenant/")   // InMemoryKms() in tests
val keys = TenantKeys(TenantTransactions(app, system), kms, KeyCache(soft = 15.minutes, hard = 60.minutes))

keys.provision(KeyOwner.Tenant(acme))         // a root, and version 1 of enc and bidx; a second call does nothing
keys.current(KeyPurpose.Enc)                  // the bound tenant's active key, for sealing
keys.version(KeyPurpose.Bidx, 3)              // an older version, to open or look up what it wrote
keys.rotate(KeyOwner.Tenant(acme))            // version n+1 of each, now active; older ones still open
keys.retire(KeyOwner.Tenant(acme), KeyPurpose.Enc, 1)   // once no row refers to version 1
keys.shred(KeyOwner.Tenant(acme), waiting = 30.days)    // deletes its rows, schedules its root's deletion
```

Every call answers `Either<KeyError, _>`: `Unavailable` when the KMS cannot be reached, `Denied` when it refuses (a
deleted root, a policy), `Invalid` when the key, root or context is wrong, and `NoTenant` when none is bound.

`KeyOwner.Platform` owns the keys of data that belongs to no tenant, such as the service's own staff. Its rows carry
`tenant_id = ''`, which no `TenantId` can be, and are read in an audited system transaction. The policy never
matches `''`: a pooled connection that a tenant transaction has used reads `app.tenant_id` as `''` once it ends, so
without that test any query on it outside `TenantTransactions` would see the platform's rows.

## Explicit operations

The neutral `KeyOperations` contract and `InMemoryKeyOperations` are available from 0.17.0.
The real AWS implementation of these explicit operations follows separately (spec 0260).

`KeyOperations` adds explicit roots beside `Kms`, through `DataKeyOperations` and `RootOperations`.
`EncryptionContext` snapshots exactly the supplied entries, including platform conventions; it inserts no defaults.
Generation returns a 32-byte `GeneratedKey`; wrap preserves supplied bytes, and unwrap requires the same root/context.
Returned key arrays belong to the caller for erasure, as in the existing crypto API (spec 0254).

Root discovery, full tag reads and listing are observational. Multi-region primary creation requires explicit
policy, description and tags;
alias binding, rotation and replica creation/inspection are separate resumable operations. Metadata carries regional
relationships and pending states. Requests and evidence containers redact sensitive contents when rendered.
`KeyOperationFailure` contains only an operation and closed diagnostic code, independently of `KeyError`.

Deletion takes an `ExpectedKeySet` and whole waiting days (7–30). All locations, relationships and required tag
subsets must match before scheduling; replicas go first, aliases remain, and retries preserve accepted dates.
`DeletionProgress` retains regional acceptances alongside a stopping failure; dates can be unknown. Discovery
cannot prove destruction. The AWS implementation follows separately (spec 0260).

`InMemoryKeyOperations` takes an injected `Clock` and keeps related regional roots under the same wrapping material.
Its `failNext` and `setState` controls model incomplete observations, lost replies and pending regional progress;
`creation` and `rotationEnabled` expose creation evidence without rendering policy or tag contents.
It does not prove IAM or eventual consistency, and advancing time never deletes a root automatically.

## Sealing values

```kotlin
val cipher = TenantCipher(keys)
val place = Place(table = "customers", column = "email", row = customerId.toString())

val sealed = cipher.seal(email.toByteArray(), place)   // Either<SealError, ByteArray>, for a bytea column
val opened = cipher.open(stored, place)                // the value, or why not
cipher.reseal(stored, place)                           // under the active version, or null when it already is
```

A value is sealed with AES-256-GCM under the active `enc` version, with a fresh random nonce. The stored bytes are a
format byte, the key version, the nonce, the ciphertext and its tag, so `open` finds the version it needs after a
rotation. The tenant, table, column and row are bound in as associated data: copied to another row, column, table or
tenant, or altered by a byte, a value fails as `SealError.Tampered`, logged and counted, never as an empty value. A
value under a retired version is `SealError.UnknownVersion`.

Values are padded to a multiple of 16 bytes, so a stored length says a bucket rather than how long a name is;
`TenantCipher(keys, pad = false)` turns it off for values of fixed length. Before retiring a version, a job rewrites
each row with `reseal` in chunks, on `surefold-jobs`, until none refers to it. `tenancy.crypto.encryptions` counts
seals by owner and version; rotate a version long before it nears 2^32 seals.

## Looking values up

A sealed column cannot be searched, so a column that is looked up by equality gets a second one beside it, its blind
index:

```kotlin
val index = BlindIndex(keys)
val email = Field("customers", "email")

index.of(normalised, email)            // store in email_bidx, beside the sealed email
index.candidates(normalised, email)    // where email_bidx = any(?): one per live version
```

An index is `HMAC-SHA-256` under the tenant's `bidx` key, which never seals, of the table, column and value. The same
email in two columns, or in two tenants, indexes differently. `candidates` answers one index per version not yet
retired, so a row written before a rotation and one written after are both found; reindex rows with `of` before
retiring a version, as for `reseal`. Pass the value normalised: an email lower-cased, a phone as E.164.

What an index leaks: anyone who can read the table sees which rows hold equal values, and how often one recurs, so a
column with few distinct values (a country, a yes or no) gives itself away and should not be indexed. It does not leak
the value, and without the `bidx` key no one can test a guess against it. Prefix, fuzzy and name search leak far more,
and are not offered.

## The table

Include the changelog after `surefold-tenancy`'s: `<include file="surefold/tenancy-crypto/postgres.sql"/>`, or on
Flyway `flyway(SurefoldSchema.TENANCY_CRYPTO, …)`. It creates `surefold_tenant_keys` with row-level security forced
and the tenant policy every tenant table gets, so a tenant's transaction reads its own key rows and no one else's:

```sql
create policy tenant_isolation on surefold_tenant_keys
    using (tenant_id = current_setting('app.tenant_id', true))
    with check (tenant_id = current_setting('app.tenant_id', true));
```

Grants are the service's. The application role needs `select, insert, update, delete` on the table; a key's bytes
are only ever stored wrapped.

## The cache, across processes

`KeyCache` keeps unwrapped keys in memory: refreshed from the KMS after `soft`, still served up to `hard` while the KMS
cannot be reached, and refused after. Past its size the least recently used tenant's keys go, zeroed. Each caller gets
a copy of the bytes, so use a `DataKey` for one operation and let it go.

A rotation, a retirement or a shredding evicts the owner in the process that made it, and notifies
`TenantKeys.CHANNEL` as it commits. Every other process evicts on that notification with `surefold-postgres-notify`:

```kotlin
PostgresNotifications.listen(listenSource, setOf(TenantKeys.CHANNEL))
    .runFold(Unit) { _, notice -> if (notice is Notice.Received) keys.evictNotified(notice.payload) }
    .start(Forks())
```

Without it, another process uses the old version for at most `soft`.

## AWS KMS

`AwsKms` makes one symmetric customer managed key per owner, found by `aliasPrefix` plus the tenant id (or
`platform`). Tenant ids that cannot name an alias, anything beyond letters, digits, `/`, `_` and `-`, are refused.
Every data key is wrapped with the encryption context `{tenant_id, purpose, version}`, or `{platform, purpose,
version}`, and KMS refuses an unwrap under any other.

Give only the worker that provisions, rotates and shreds the rights to make keys, and the application only the right
to unwrap under this prefix. The application role's policy:

```json
{
  "Effect": "Allow",
  "Action": "kms:Decrypt",
  "Resource": "*",
  "Condition": {
    "ForAnyValue:StringLike": { "kms:ResourceAliases": "alias/bookings/prod/tenant/*" },
    "Null": { "kms:EncryptionContext:purpose": "false", "kms:EncryptionContext:version": "false" }
  }
}
```

The worker's adds `kms:CreateKey`, `kms:TagResource`, `kms:CreateAlias`, `kms:DescribeKey`, `kms:GenerateDataKey`,
`kms:ListAliases`, `kms:DeleteAlias` and `kms:ScheduleKeyDeletion`, under the same alias condition where KMS
supports it.

## Shredding

`shred` deletes the owner's key rows and schedules each root it named for deletion after `waiting`, which KMS rounds
into 7 to 30 days, in one transaction: a KMS that refuses leaves the rows. Until KMS deletes the key, an operator can
cancel the deletion; after, nothing can unwrap the tenant's keys, in the live database or in any copy of it.
