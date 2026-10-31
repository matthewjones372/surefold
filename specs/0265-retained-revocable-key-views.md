# 0265 — Retained keys with revocable owned views

Approved ownership/terminal/ledger/groups contracts are on main, unreleased; public cache delivery remains unimplemented.
Checked mainb5aa070e / published0.17.0. B1 PR960/main41200432232 passed. Retention/release/adoption remain incomplete.

## Problem

KeyCache deliberately gives independent DataKey copies. Ordinary eviction cannot revoke a copy already in use.
Applications needing both degraded operation and immediate revocation otherwise duplicate key lifetime machinery.
KeyCache must retain its Unavailable-only fallback and owner budget; generic Cache has different policy (spec0246).

## Not doing

Nothing to SQL, schemas, authority/journals, codecs, provider transports, application policy, or default time/capacity.
No replacement of KeyRing.current/version/versions, old copy semantics, new module or generic lease framework.

## Shape

Identity/view types are on main, unreleased; the cache members below remain proposed. Loader stays today's function, not KeyLoader.
```kotlin
class KeyIdentity(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int, revision: ByteArray)
interface KeyView : AutoCloseable {
    val identity: KeyIdentity
    fun <A> using(block: (ByteArray) -> A): Either<KeyError, A>
    override fun close()
}
// Proposed members of existing KeyCache; its constructor and existing members stay unchanged:
fun lease(identity: KeyIdentity, load: () -> Either<KeyError, DataKey>): Either<KeyError, KeyView>
fun revoke(owner: KeyOwner)
fun revokeAll()
fun closeAndJoin()
```
KeyIdentity defensively owns nonempty revision bytes, content equality/hash and fixed value-free toString;
owner/purpose/version are read-only; version is positive. No revision byte getter or mutable collection escapes.
Revision is the caller's immutable durable-key identity, not an epoch or a hash the library invents/authorises.
The caller guarantees interchangeable loaders for equal identities; the returned DataKey metadata must match.
Legacy unqualified Slot/get and qualified lease have distinct tagged flight/retention modes: neither can satisfy
the other, even for the same owner/purpose/version. Old handed-out DataKey copies remain UNREVOCABLE.
Freezing a load group's Value or original Thrown outcome closes NEW enrolment; only pre-enrolled receipts retain it.
A delayed enrolled caller after SingleFlight key removal replays it without reloading; a fresh caller starts a new group,
never a negative-cache hit. Explicitly record/replay the original Throwable, including CancellationException; Resource's
Completed exit alone cannot classify outcomes. Loader and ALL enrolled copy/view handoffs or cancellations own the source.
Enrolment holds a receipt, never a loader anchor. Worker entry claims it; final receipt retires an unentered group.
A late worker for a retired group throws private CancellationException("the key load is no longer admitted"), without loading.
Closed/start-failed SingleFlight before entry has no loader anchor; caller Resource releases its receipt. Pre-registered
loader finish covers every entered exit; before outcome commit it closes with the original throw, then releases its anchor.
Committed Frozen outcome is immutable/source-owned. Captured loader outcomes replay; later local bookkeeping failures
still propagate with cleanup suppressed. Current SingleFlight waiters may see such a post-commit throw while a delayed
accepted receipt replays committed Value; uniform instrumentation-failure replay is not promised.
Scoped inherited active-group bindings refuse direct/indirect reentry of that group with fixed IllegalStateException
("recursive key load") before waiting; distinct group epochs are allowed. No general SingleFlight change is introduced.
Both modes share ONE owner LRU at the instance capacity (default1000), evict/revoke retention and one retained tagged value per logical slot
(owner,purpose,requested version; the legacy active alias keeps its existing null version). Revision variants add
no retained map entries. Each accepted load has a monotonic slot ticket; only the highest-ticket successful Right
may replace retention. A slower older-ticket Right answers its own callers unretained, never overwriting it.
Failure never advances the successful-ticket watermark. Denied/Invalid/NoTenant drop captured last-good ONLY while
mode/identity/ticket/epoch/record-ID still match current retention, preventing its later reuse; newer/different records stay.
Fallback never advances the successful watermark or resets key age.
Tickets order cache admission, never opaque revisions. Forgotten/revoked epochs fence late publication; later
admissions cannot join an earlier epoch's flight. Different qualified identities never share material/fallback.
lease uses the call-bound clock and instance soft/hard lifetimes (defaults15m/60m); no extra capacity, failure TTL or identity ordering is added.
Only Unavailable may reuse that identity's last good key before hard; Denied/Invalid/NoTenant refuse/drop it.
Each view captures the injected clock.get() at its lease admission; later using uses that SAME Clock, not its
thread's current binding or Instant.now. Material's age/deadline starts on its load's captured clock before load;
fallback/view creation never resets it. After waiting a loader, recheck the original good material's deadline:
Unavailable begun at55m but answered at61m refuses, even if it was usable when loading started.
Legacy get also rechecks its original hard cutoff when Unavailable returns, answering the ORIGINAL loader Left beyond hard.
Its independent-copy/ABI contract stays; no additional legacy cold-Right refusal policy is introduced.
Every using checks hard expiry at entry AND after a normally returning callback, returning fixed
KeyError.Unavailable("the key view has expired") at/after deadline; expired cold load also refuses/wipes.
Failure/expiry cleanup may remove ONLY its owned source matched by mode/identity/ticket/epoch, never newer
retention, including a newer publication of the SAME identity. No caller side effects are rolled back by guards.
Old get/Slot, KeyCache constructor/defaults, KeyRing/DataKey descriptors and old independent-copy behavior remain.

The loader owns temporary/partial bytes until Right returns; then ownership of that DataKey material transfers.
The cache owns every transferred array even on metadata refusal, cancellation, admission/publication rejection.
A view owns a revocable guarded borrow, never an independently exported DataKey. using exposes material only
for its callback; retaining/copying it outside that contract is unsupported. Original callback defects/cancellation
propagate unchanged. Closed/revoked using returns KeyError.Denied("the key view is unavailable") before entry.
If revoked during a normally returning callback, it returns that fixed Denied, never success. close is idempotent.
Each same-key concurrent using gets operation-owned working bytes; wipe them on every callback exit. Closing a
view prevents new uses; an admitted callback may return normal A after benign close/forget ONLY while neither
revoked nor expired, then its bytes wipe. Revoke gives fixed Denied; expiry gives fixed Unavailable.
Original callback defects/cancellation always propagate; the post-callback guard cannot undo escaped side effects.
Existing evict(owner) forgets BOTH modes' retention: borrowed views may finish; bytes wipe once ownership permits.
revoke(owner)/revokeAll invalidate affected held views including previously forgotten ones and best-effort-zero
their owned copies; it drops both modes' retention but cannot revoke legacy copies. A qualified load paused
across revoke returns fixed Denied, unkept; a legacy load may still return its old-style independent copy, unkept.
Forgetting across a load suppresses retention but may answer accepted borrowers. Every unretained transferred
array has a terminal owner and wipe: no onEvict-only assumption, abandoned flight copy or orphaned view.
A prepared Copy/View is provisional until ALL receipt cleanup returns normally. An outer Resource owns raw copied
bytes or the exact borrowed handle before further fallible work, disarming only at final return; failed abort remains
an exact owned obligation. The groups-only increment exposes no delivery; its separate return seam must prove this boundary.
Private terminal notices follow wipe when forgotten material has no views/uses, or revoked material has no uses;
forgotten live valid views remain reachable for later revoke. Notice/release intents stay Pending until outside cleanup
returns normally and Ack commits. Action or uncommitted-Ack failure stays Pending; committed Ack persists despite a
post-commit instrumentation error. Never resurrect acknowledged IDs or overwrite another/newer record; preserve primary/
suppressed failure identity. Each drain snapshots owed IDs once and attempts every snapshot action once despite errors;
failed actions remain Pending, with no same-call loop over new/restored IDs.
closeAndJoin closes admission, revokes views, cancels/joins accepted loaders and waits admitted using callbacks;
idle handles need not close. It completes cleanup despite interruption, restores interruption and retains failures.
Calling it from its own loader/using is refused by fixed IllegalStateException before changing admission.
Later lease returns fixed Denied; old internal get after close throws fixed IllegalStateException. No forced timeout.
Use existing SingleFlight/Shared/Resource and scoped SurefoldLocal bindings; no effects in retryable Shared callbacks
or new executor/scheduler.

## Provisional delivery and early exact ownership

The delivery entry requires a separately approved two-method core HandoffDecision<A:Any> (spec0269) (tryComplete/peek).
It is proposed, NOT shipped; no Promise/Shared instrumentation is disabled. There is no waiting or wakeup API.
Existing internal OwnedKeyMaterial.borrow(clock) keeps its descriptor/behavior. An additive INTERNAL overload
borrow(clock,beforeAdmission:(closeExact:()->Unit)->Unit) allocates one exact token/close capability and registers
local rollback BEFORE invoking that callback OUTSIDE Shared and BEFORE admitting token membership. The callback
only registers ownership. A failed precommit registration admits no view; committed registration plus observer
throw retains the exact capability. Admission commit plus failed rollback before commit leaves that SAME cap owned.
Close removes only that token, idempotently, preserving all expiry/revoke/terminal-notice laws; never reopen it.
A drainer NEVER executes a Preparing capability. Only its caller can Abort after its admission scope has ended,
so cleanup cannot close before admission and then allow a resumed caller to add membership again.

One ledger row owns a fresh private ID, quiet decision and preallocated exact release intent/capability. Pending
is peek()==null; only the owning caller chooses Delivered/Abort. No caller-reusable ID, tombstone or second registry.
For legacy copies, preregistered local Resource captures raw bytes immediately after copyOf and before wrappers;
ordinary fill owns initial-registration failure. Qualified close needs the durable early capability above.
One domain delivery method encloses enrol.use AND ALL receipt/loader/drain cleanup. Its outer Resource owns abort
before acquisition. After normal inner return only, mark Delivered as the final effect and return the prebuilt value;
its outer release only peeks/no-ops. Any earlier defect/cancellation marks Abort first, then attempts fallible cleanup.
Keep original Throwable identity and distinct suppressed cleanup; CancellationException is not inferred from ExitCase.
Typed Left never disarms abort as a successful delivery. No post-Delivered fallible bookkeeping is permitted.

Bounded drain snapshots rows/pending intents ONCE. Preparing skips; Delivered reaps metadata ONLY. Abort transfers
its exact cap/token atomically to pending and removes the row: that token is always owned in row OR pending.
Precommit transfer failure retains the row; postcommit throw retains pending. Action precedes exact-token Ack;
committed Ack never resurrects/replays, including after a metric throw. If transfer AND direct exact-close both fail
before commit, the Abort row survives for later drain. Every snapshot action is tried once despite errors; new work
waits for next drain. Idempotent fallback may repeat only its own close, never another handle's. Delivered never
runs abort, even if metadata reap fails. Ordinary source revoke/expiry is separate; legacy copies stay unrevocable.
Active/failed obligations retain records; no finite-memory promise under infinite faults/unbounded admissions.

This marker does NOT solve accepted-caller join. BEFORE joined-release/R4 source, a separate concrete reviewed
contract must track every accepted caller boundary (including enrolled callers before capability registration),
fence admission, reject direct/indirect self-close before fencing, preserve interruption and complete all owed
cleanup. SingleFlight joins its workers only. No await/latch/wakeup/polling design or caller-thread join is credited.

## Why this shape

Extend KeyCache additively: its per-call loader/clock, selective stale and owner bound already fit (PR903).
Alternative: a general retained-resource cache; broader than this key requirement. Recommend this crypto-only view.

## Stack

- [x] **`codex/key-view-contract-20261031`** — reviewed spec on main, PR956.
- [x] **`codex/key-view-ownership`** — identity/private material/view guards, PR957/main e7af.
- [x] **`codex/key-view-retention`** — terminal notices/acknowledgements, PR958/main9e9aec92; no cache/public activation.
- [x] **`codex/key-view-retention-ledger`** — pending release intents/acknowledgements, PR959/main80f3873b; legacy class unchanged; ~78 main/spec lines.
- [x] **`codex/key-view-retention-groups`** — groups/outcomes/source anchors ONLY, PR960/main1d574300; no delivery.
- [ ] **`codex/handoff-decision`** — separately numbered core spec/API, tryComplete/peek ONLY; no await/join.
- [ ] **`codex/key-view-early-close`** — additive INTERNAL exact-capability registration before admission; old descriptor retained.
- [ ] **`codex/key-view-retention-delivery`** — full provisional-return/abort protocol above; exact plan/count before source.
- [ ] **`codex/key-view-retention-policy`** — shared owner LRU, modes, success tickets and epochs; no public lease; ~175 main lines.
- [ ] **`codex/key-view-retention-legacy`** — old get/evict integration and protective Unavailable cutoff; ~185 changed main lines.
- [ ] **`codex/key-view-retention-leases`** — lease/revoke/revokeAll, all caller handoffs and array exits; ~180 main lines.
- [ ] **`codex/key-view-release`** — joined close, old source/binary fixtures and compiled guide; no consumer pin yet.
Each implementation entry stays under~200 changed main/build/docs lines; split/review scope before exceeding it.
No snapshot consumer is enabled before the complete reviewed stack and required main checks pass.

## Acceptance

```bash
./gradlew :surefold-tenancy-crypto:check :surefold-app:check
./gradlew spotlessApply && ./gradlew build
```
Old copies survive evict; new views survive forget, refuse revoke before/during use and across a paused load.
59m Unavailable succeeds/61m refuses; paused55→61m fallback refuses without resetting age; held-view using at
hard refuses before callback, and a callback crossing hard cannot return Right. Capture-clock rebinding is inert.
Denied/Invalid/NoTenant never degrade; changed identity never degrades; old-epoch failure/expiry cannot drop newer
SAME-identity retention. Benign close admits normal callback result only before expiry and absent revocation.
Pin Denied→Unavailable without stale reuse; stale failure must preserve newer success. A new caller cannot join a frozen
failed group; a delayed enrolled caller replays original CancellationException. Assert action-failure Pending versus
committed-Ack/no-replay, and all snapshot cleanup actions attempted once even when preceding ones fail.
Assert one load, owner LRU, every transferred/working array wiped, defects/cancellation identity and joined close;
owned Promise gates always release/join. No sleeps, lowered coverage/dependency gates or algorithm/provider change.

## Open questions

Resolved after independent review: extend KeyCache rather than add a second cache; close joins accepted work,
not idle handles; material access is callback-bounded, with no escaped-copy/JVM-erasure guarantee. Tagged modes,
one retained identity per slot, successful-ticket publication, original hard expiry and benign close versus
revoke follow the Shape above. The delivery entry's pre-acquisition abort ownership needs its own reviewed
implementation plan before code; the groups-only entry exposes no delivery. Accepted-caller join remains an
OPEN design obligation requiring a separate concrete contract before joined-release/R4 implementation.
