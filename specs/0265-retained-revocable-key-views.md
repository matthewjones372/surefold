# 0265 — Retained keys with revocable owned views

Internal ownership/terminal/ledger/groups/B2 delivery are on main, unreleased; public cache delivery remains unimplemented.
B2 PR979 headfa41c4d89f57fc77afadffa3879727fbf118b9a5 merged91040b935a7ae84b7be9c6c89e5e268cb3b48899;
own-main41281917030 succeeded at that merge. R4/retention/public release/adoption remain incomplete.

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
Its source is on main via PR973/main46213a81, unreleased; no existing instrumentation is disabled. There is no wait/wakeup API.
Existing internal OwnedKeyMaterial.borrow(clock) keeps its descriptor/behavior. An additive INTERNAL overload
borrow(clock,beforeAdmission:(closeExact:()->Unit)->Unit) allocates one exact token/close capability and registers
local rollback BEFORE invoking that callback OUTSIDE Shared and BEFORE admitting token membership. The callback
only registers ownership; it does not invoke/drain the Preparing capability or recursively borrow the material.
A failed precommit registration admits no view; committed registration plus observer
throw retains the exact capability. Admission commit plus failed rollback before commit leaves that SAME cap owned.
Close removes only that token, idempotently, preserving all expiry/revoke/terminal-notice laws; never reopen it.
A drainer NEVER executes a Preparing capability. Only its caller can Abort after its admission scope has ended,
so cleanup cannot close before admission and then allow a resumed caller to add membership again.

One ledger row owns a fresh private ID, quiet decision and preallocated exact release intent/capability. Pending
is peek()==null; only the owning caller chooses Delivered/Abort. No caller-reusable ID, tombstone or second registry.
For legacy copies, preregistered local Resource captures raw bytes immediately after copyOf and before wrappers;
ordinary fill owns initial-registration failure. Qualified close needs the durable early capability above.
Internal KeyCacheLedger.Delivery has Copy(key:DataKey) and View(view:KeyView).
deliver(key:LoadKey,capturedClock:Clock,deadline:Instant,flights:SingleFlight<Any,KeyError,Unit>,
load:()->Either<KeyError,DataKey>):Either<KeyError,Delivery> alone encloses enrol.use AND ALL receipt/loader/drain
cleanup. SingleFlight is keyed by receipt.group, never a reusable load key; no unsafe handoff method escapes.
B2's outer Resource owns abort before acquisition; delivered source marks Delivered after normal inner return.
R4 below moves that publication beyond ALL owned Resource/binding unwind. Earlier defect/cancellation marks Abort
first, then attempts exact cleanup. No implemented R4 caller join is inferred from B2's existing outer release.
Keep original Throwable identity and distinct suppressed cleanup; CancellationException is not inferred from ExitCase.
Typed Left never disarms abort as a successful delivery. No post-Delivered fallible bookkeeping is permitted.

Bounded drain snapshots rows/pending intents ONCE. Preparing skips; Delivered reaps metadata ONLY. Abort transfers
its exact cap/token atomically to pending and removes the row: that token is always owned in row OR pending.
Precommit transfer failure retains the row; postcommit throw retains pending. Action precedes exact-token Ack;
committed Ack never resurrects/replays, including after a metric throw. If transfer AND direct exact-close both fail
before commit, the Abort row survives for later drain. Ordinary drain tries every snapshot action once despite errors;
R4 close alone has the exact-phase interruption retry exception below. New work waits for next drain. Idempotent fallback may repeat only its own close, never another handle's. Delivered never
runs abort, even if metadata reap fails. Ordinary source revoke/expiry is separate; legacy copies stay unrevocable.
Active/failed obligations retain records; no finite-memory promise under infinite faults/unbounded admissions.

## R4 accepted-caller boundary (spec-only; no implementation credit)

This amendment promotes independently Top-reviewed consumer proposal26595fcbd. Exact-spec review/main and a separate
reviewed committed source plan precede source. SingleFlight joins workers, not accepted callers;0269 has no wake API.
Call proposed0273 checkNotInTransaction before admission/close. No ownership effect runs inside ambient transactions.
One private Shared per owner holds open/closed plus exact preallocated tickets, each with HandoffDecision<Unit> Finished.
Atomic ticket registration while open is acceptance, BEFORE enrolment, hits, capability registration or using entry.
A thrown admission reconciles exact identity through committed state.peek(); postcommit throw still owns its ticket.
Precommit refusal runs no body; outer plain finally publishes Finished even if acquisition failed. Retryable callbacks
contain no effects, binding changes, cleanup or quiet-marker operations. Observe Finished outside Shared and remove
only those exact observed terminal identities in later admission/drain/close; never remove a pending ticket.
The owner identity covers admission and its observers/reconciliation, body and resource cleanup, and every outer
abort attempt. No fallible owner cleanup runs with that identity absent. Every binding restoration precedes Finished;
only quiet terminal publication and plain return/rethrow follow. Direct/inherited indirect self-close throws fixed
IllegalStateException("a key cache caller cannot close its owner") BEFORE fencing, even if already closed.
Finished is distinct from Delivered/Abort: Abort precedes exact fallible cleanup; Finished follows all unwind/attempts,
not successful discharge of every obligation. Delivered occurs only after normal completion of ALL owned Resource
and binding scopes, using a prebuilt answer; only quiet Finished and plain return follow. Any unwind failure selects
Abort. No Resource/binding wrapper surrounds this final tail. No physical caller-thread join or idle-handle wait.
A generic wrapper around today's deliver is insufficient: it still publishes Delivered before wrapper restoration.
The final boundary owns both markers; carry a preallocated internal disposition/value packet, no finalizer callback.
Capture original body failure INSIDE the bound block before Bindings.under's finally can mask it. Reconcile outside:
original wins, distinct restoration/abort failures are suppressed. Re-establish identity before outer exact abort,
capturing its failure before that binding restores too. Failed binding installation runs no unowned cleanup; retain
the exact obligation/failure. Restore every acquired binding before Finished. This is no reproduced ThreadLocal leak.
After inherited self-close refusal, close establishes its own owner identity through fence observers/reconciliation
and stop/observe/drain; preserve failures across its restoration too. A closer is not a ticket waiting on itself.
Close atomically sets closed and captures accepted tickets. Closed is monotonic; concurrent/repeated close reconciles
committed state, never a previous return/throw. Postcommit fence error still triggers cleanup. Precommit interruption
is remembered/cleared and retried; noninterrupt failure is retained and cannot produce successful close. Even failed
fencing independently attempts stop, a fresh committed ticket snapshot observation and drain, then reports failure.
Independently attempt owner worker stop/join, every captured caller's Finished, and ledger cleanup in that order;
no earlier noninterrupt failure skips later stages/items. Success requires confirmed fence, joined workers, all
accepted boundaries Finished and successful owed cleanup. Public wiring independently attempts revocation and stop.
For each incomplete full ticket scan, call existing SystemClock.sleep(10.milliseconds) DIRECTLY outside transactions;
retain owner identity. Do not use injected clock/fixedClock, yield or timeout. Waiting.sleep uses monotonic timed
parking, including spurious-wake rechecks; fixedClock.sleep does not wait. Key expiry retains its captured Clock.
This10ms observation cadence is selected, not a measured optimum or latency guarantee; no caller notification.
Remember/clear interruption at entry and after EVERY stop/wait/transfer/action/Ack attempt, normal or throwing.
Normally returning SingleFlight.closeAndJoin restores its flag: remember/clear it, do not repeat successful stop,
then observe/drain. Restore interruption once at outer exit. Finite interruption must not defer owed cleanup.
Close's explicit exception to ordinary once-per-snapshot drain retries INTERRUPTION-ONLY unresolved captured phases.
Freeze exact IDs once after applicable fence/worker/caller stages; never resnapshot or recruit new/restored IDs.
An Abort row's transfer and resulting pending intent are ONE captured obligation, not a new ID. Reconcile exact
row/intent/Ack after errors. Interrupted stop retries the SAME owner workers. Interrupted transfer retries only the
unresolved transfer; interrupted action may repeat only its exact idempotent action if still unacknowledged.
After an action returns normally, retain action-completed phase: an interrupted precommit Ack retries Ack ONLY.
Committed Ack never replays/resurrects, even if its observer throws interruption or a noninterrupt failure.
Any mixed/noninterrupt fault stops retries for that item, retains only unresolved obligations, and permits attempts
of other items/stages. Inspect the complete carried failures: interruption with suppressed noninterrupt is MIXED.
An existing caller/body failure wins, including InterruptedException/CancellationException/Fail. Otherwise first
noninterrupt close failure wins; all other distinct throws, including remembered interruptions, are suppressed by
identity in observation order. Interruption alone becomes restored status after cleanup, not a replacement throw.
No invented throwable for a flag-only interrupt; no self-suppression, defect retry or cancellation/Fail misclassification.
No finite completion under unbounded interruption/unresponsive callers, no forced timeout or VM-fatal guarantee.

### Required source-plan gate

Fail cleanup diagnostics require accepted spec0292 before caller-boundary source: preserve the same opaque
unwind signal with ordered cleanup suppression and unchanged owning Left(E). Diagnostics exist only before
the owning attempt consumes its signal; this adds no post-attempt error API. All24 R4 laws, E/S/D proof modes,
ownership and D/C/P scopes remain unchanged. B1/B2 split and exact test-fit approval remain separate gates.

Keep the internal owner in existing KeyCache.kt (provisional name KeyCallers); no new public crypto API in these
internal entries, second flight pool, per-call thread, generic helper or changes to Bindings/SurefoldLocal/Resource.
Before source, independently review and commit exact packet/ticket fields and frame nesting: identity installed
before admission; original failure capture inside EACH relevant binding; restoration reconciliation/rebound abort;
failed-install ownership retention; each Resource owner; every restoration before the compiled quiet terminal tail.
Expose exact transfer/action/Ack phases to close's fixed-snapshot driver: opaque whole-drain retries are insufficient.
Show retained action-completed phase and concurrent close/drain committed-Ack reconciliation without reused IDs,
new tombstones or resurrected intents. If existing APIs cannot express it, stop for exact contract/scope amendment.
Public get/lease/using/close wiring waits for retention predecessors, then attaches ONE owner to every path and
ALL owner workers, including cache hits and forgotten views; an arbitrary supplied flight is not public close proof.
Revocation still rejects normally returning revoked uses. New IDs outside the fixed snapshot cannot earn completion:
public plan proves none remain after quiescence or specifies fixed incomplete-cleanup failure before source. That
question cannot defer interruption-only cleanup or weaken the full public close guarantee. Pins remain prohibited.

### R4 acceptance and negative controls

Each row needs meaningful RED then GREEN; missing-API compile is separate. Preserve all B2 cases and gates.

| Required acceptance | Negative control that must fail |
|---|---|
| Accepted caller paused BEFORE enrol/capability keeps close waiting | Tracking Prepared/workers alone returns early. |
| Atomic admission/fence race includes earlier ticket, refuses later body/load | Separate snapshot/check misses a caller. |
| Admission commit then observer throw preserves identity, reconciles ticket, runs no body, reaches Finished | Treat thrown modify as uncommitted. |
| Fence commit then observer throw still refuses admissions and attempts all cleanup | Return early from fence error. |
| Precommit fence interruption retries with cleared flag, restores at exit | Restore too early or return prematurely. |
| Noninterrupt precommit fence fault attempts later cleanup but cannot succeed | Swallow fault/credit unconfirmed fence. |
| Captured action precommit interrupt retries exact action this close, completes other IDs | Once-only deferral/whole-drain resnapshot. |
| Normal action, interrupted precommit Ack retries Ack only | Composite retry repeats the action. |
| Committed Ack then interrupt never replays, other IDs attempted | Reconstruct acknowledged obligation. |
| Normal SingleFlight stop with restored interrupt permits drain commit and final restored flag | Pass restored flag into drain. |
| Interrupt with suppressed noninterrupt; noninterrupt after earlier interrupt | Mixed failure retried/spun or primary replaced. |
| Direct/inherited caller/load/using self-close refused before fence; other owner may close | Worker-identity-only guard. |
| Admission observer/reconciliation recursively closes: refusal, no fence, ticket reconciled | Bind identity only around body. |
| Outer abort after restored/failed binding recursively closes: refusal under rebound identity | Abort without owner identity. |
| Body failure plus restoration failure preserves original; failed abort binding retains obligation | Outside-only catch loses body error/unowned cleanup. |
| Paused Resource/binding unwind keeps Finished pending | Publish inside release/before restoration. |
| Prepared value then unwind throw: no Delivered, exact Copy wiped/View abort owned | Keep B2 early Delivered. |
| Callback interrupt/cancellation/Fail plus cleanup failure preserves original; later actions run | Classify from Completed/stop early. |
| fixedClock closer genuinely TIMED_WAITING/parked; released caller finishes | Injected fixedClock polls without park. |
| Spurious wake/interrupt waits for all Finished, clears before next park, restores flag | Treat wake as completion/spin. |
| Stop throws while caller held: still observe it, then attempt drain | Fail-fast stop/observe/drain chain. |
| Repeated/concurrent closers, terminal reaping, no-work close | Forget/remove pending ticket. |
| Noninterrupt abort fault survives Finished for later drain; committed Ack stays final | Equate Finished with wiped/released. |
| Ordinary/nested/forced-solo transaction refuses before any ownership/body/fence | Omit0273 guard. |

Own/release all Promise gates and join workers even on failure; public Surefold primitives, TestClock expiry,
passive getBlocker/thread-state parking observations, outer real-clock eventually deadline with closer fixedClock.
No test sleeps/hooks/raw latches/threads; observe the actual closer, not a helper's park. Global-Solo faults use
reviewed isolated suite admission. A synthetic unwind failure is not a ThreadLocal leak reproduction; actual trace
concern remains source-level on earlier Temurin25.0.4.1+1, not new CI25.0.5+8 proof. Resource self-suppression
concern stays withdrawn. Either.catch filters interrupt/fatal/Fail, so terminal lifecycle catches require exact
sites/reasons/identity tests in the source plan; no capture helper or throw-in-mapLeft workaround. No erasure or
performance/check-saving claim. Preserve typed detekt, Kover90%, dependencies, formatting and full PR/main gates.

## Why this shape

Extend KeyCache additively: its per-call loader/clock, selective stale and owner bound already fit (PR903).
Alternative: a general retained-resource cache; broader than this key requirement. Recommend this crypto-only view.

## Stack

- [x] **`codex/key-view-contract-20261031`** — reviewed spec on main, PR956.
- [x] **`codex/key-view-ownership`** — identity/private material/view guards, PR957/main e7af.
- [x] **`codex/key-view-retention`** — terminal notices/acknowledgements, PR958/main9e9aec92; no cache/public activation.
- [x] **`codex/key-view-retention-ledger`** — pending release intents/acknowledgements, PR959/main80f3873b; legacy class unchanged; ~78 main/spec lines.
- [x] **`codex/key-view-retention-groups`** — groups/outcomes/source anchors ONLY, PR960/main1d574300; no delivery.
- [x] **`codex/handoff-decision`** — PR973/main46213a81; tryComplete/peek ONLY, unreleased; no await/join.
- [x] **`codex/key-view-early-close`** — PR977/main e47c38be: additive INTERNAL before-admission exact cap; old descriptor retained.
      All36 previous cases plus8 new ownership laws pass; existing expiry/revocation/terminal laws retained.
- [x] **`codex/key-view-retention-delivery`** — B2 PR979/main91040b93, own-main41281917030 SUCCESS; internal only.
- [ ] **`codex/key-callers-precode-20261102`** —0273 guard plus this R4 amendment, exact-spec review/spec-only main.
- [ ] **`codex/transaction-boundary-guard`** —0273's one-method prerequisite; target<60 main/docs lines.
- [ ] **`codex/fail-suppression-contract`** —spec0292 plus this dependency amendment, exact review/spec-only main.
- [ ] **`codex/fail-suppression`** —spec0292 one-flag prerequisite; accepted RED/GREEN, full PR/actual-main gates.
- [ ] **`codex/key-callers-boundary`** —internal tickets/fence/wait and tests; target<170 main lines, no activation.
- [ ] **`codex/key-callers-delivery`** —B2 outer packet/return/unwind integration; target<140 main lines.
- [ ] **`codex/key-callers-close-drain`** —exact-phase interruption retries/Ack reconciliation; target<160 main lines.
- [ ] **`codex/key-view-retention-policy`** — shared owner LRU, modes, success tickets and epochs; no public lease; ~175 main lines.
- [ ] **`codex/key-view-retention-legacy`** — old get/evict integration and protective Unavailable cutoff; ~185 changed main lines.
- [ ] **`codex/key-view-retention-leases`** — lease/revoke/revokeAll, all caller handoffs and array exits; ~180 main lines.
- [ ] **`codex/key-callers-release`** —after retention predecessors: complete public caller/using/close wiring,
      old source/binary fixtures and compiled guide; exact source/path/line-budget review first, no consumer pin.
Each implementation entry stays under~200 changed main/build/docs lines; split/review scope before exceeding it.
No snapshot consumer is enabled before the complete reviewed stack and required main checks pass.

## B2 qualification boundaries

Keep all25 existing ledger cases unchanged. Global-Solo fault cases use a SECOND same-file Suite(concurrent=false, isolated=true).
A read-only Solo holder awaits an already-running virtual Deferred; never Promise.await inside that transaction.
All Promise/Shared completions and diagnostics occur OUTSIDE the hold. Public TestClock gates, passive getBlocker
and guarded Turns observations establish actual Solo contention and the TWO real precommit interrupt checks.
Postcommit metrics failures or an interrupt flag alone cannot certify precommit failure. No production hook or hand-made synchronisation.
For an unreturned Copy, a test-only snapshot follows exactly ONE Prepared row to its ReleaseIntent action's single
NONSTATIC ByteArray capture. Confirm known exact close-action provenance, distinct source and nonzero bytes before fault.
Assert that SAME alias is zero after abort; no heap walk, private mutation, allocator/observer API or unsafe handoff.
These are reviewed test techniques, not runtime qualification. Actual meaningful RED and GREEN are required.
Ambiguous layout/access/compilation/setup failure earns ZERO semantic credit and stops for review.
No pre-registration allocation, VM-fatal, universal-agent or JIT-erasure guarantee is credited. R4 caller join remains separate.

## Acceptance

```bash
./gradlew :surefold:check :surefold-tenancy-crypto:check :surefold-app:check
./gradlew spotlessApply && ./gradlew build
```
Old copies survive evict; new views survive forget, refuse revoke before/during use and across a paused load.
59m Unavailable succeeds/61m refuses; paused55→61m fallback refuses without resetting age; held-view using at
hard refuses before callback, and a callback crossing hard cannot return Right. Capture-clock rebinding is inert.
Denied/Invalid/NoTenant never degrade; changed identity never degrades; old-epoch failure/expiry cannot drop newer
SAME-identity retention. Benign close admits normal callback result only before expiry and absent revocation.
Pin Denied→Unavailable without stale reuse; stale failure must preserve newer success. A new caller cannot join a frozen
failed group; a delayed enrolled caller replays original CancellationException. Assert action-failure Pending versus
committed-Ack/no-replay, ordinary snapshot attempts despite preceding failures, and R4's close-only interrupt retries.
Assert one load, owner LRU, every transferred/working array wiped, defects/cancellation identity and joined close;
owned Promise gates always release/join. No sleeps, lowered coverage/dependency gates or algorithm/provider change.

## Open questions

Resolved after independent review: extend KeyCache rather than add a second cache; close joins accepted work,
not idle handles; material access is callback-bounded, with no escaped-copy/JVM-erasure guarantee. Tagged modes,
one retained identity per slot, successful-ticket publication, original hard expiry and benign close versus
revoke follow the Shape above. R4 choices resolved by independent Top review of26595fcbd:0273 refusal,
direct SystemClock10ms observation, interruption-only exact-phase close retries and full inherited owner coverage.
Source-plan questions remain GATES, not implementer discretion: exact packet/binding/phase representation and
compiled tail; later public wiring/residual-cleanup failure. Commit independent reviews before each source entry.
This spec-only promotion needs exact-document review/main proof; no caller-join implementation is credited.
