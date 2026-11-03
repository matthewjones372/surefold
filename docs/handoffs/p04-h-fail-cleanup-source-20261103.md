# P04-H Fail cleanup source handover

Status: DONE_WITH_CONCERNS; feasible local checks pass, full gate unpaid; not H acceptance.
Branch: `codex/p04-h-fail-cleanup-source-20261103`.
Base: `35d2c8505dea0d7d0dc96c65402bb687e0a08b58`.
Verified source head: `1184ff8c2757ca3d3828d50625a6e91ab1f274da`; this handover follows it.
Root commission: Doorlist P04-KEY-VIEWS generation4, owner `codex-launch-director-20261102a`,
token `d63a7071a1857a66472a381b55b84181b95a0183`; network-enabled read-only guard PASS.
Initial sandbox guard wrapper rejection and Gradle cache lock denial were environmental, zero RED credit.
No claim transition, push, PR, CI dispatch, release, integration, consumer or AWS operation performed.
Approved plan SHA256: `0e708bb9fa26500564278fbf3f8767dcf6705269acb7940d3491d230b2df00a3`;
Doorlist reconciliation: `e8f1d37528b0ca9291222b18ce3de4ab732d790d`.

## Scope and public source refs

Only Failed suppression flag false→true; stack traces remain disabled. No Resource/API/routing/build change.
Diff: main1+/1-; four public-API tests158 new lines (Resource99 + Fail59), imports/blanks included.
Public attempt/resourceScope/shouldThrow/identity matchers/Shared reused; no helper or extraction candidate.
Root corrected commission wording: unchanged ordinary→Failure(primary), interrupt→Cancelled(primary),
CancellationException/Fail→Completed; no Resource behavior change.
Consumed Git blobs, checked at base before editing:
- Fail `c89192ed132b8f0dff0efdfba088bfa9026ef336`; Resource `e01af53424e16533d933d159f8bb7abac34235d8`.
- ResourceTest `49cfed68c7d8f0e80479501da43c887faddefe98`; FailTest `5f37821b944676937a0f3c555b33b0fd0cf7f7e2`.
- Matchers `7a66f7812afed05b2aa3cebf07681d94c35905bc`; Values `7b5e3ea7deeba12b38e2a981bbe94cb0d27ce21f`.
- Shared/Transact `b05bc48ffd3835c9a0267ee4f62bb9163f70b89a`.
Restored true Fail blob `685e2121ddd799391842c6010078f205e4b009c5`; false is original Fail blob above.

## TDD and checks

Focused command: `./gradlew :surefold:test --tests '*ResourceTest' --tests '*FailTest'`.
Tests first with false flag: semantic RED, compiled28 tests/3failures, not setup failures.
Resource suppression expected2/got0; foreign-attempt and Throwable-E suppression expected1/got0 each.
Matrix GREEN. True flag focused GREEN28/28. Only flag reverted: same three failures.
Forced false control added `--rerun-tasks`: compileKotlin/compileTestKotlin executed, same28/3; matrix GREEN.
Exact true source restored; focused GREEN28/28 (Resource13/0, Fail15/0). Matrix stays GREEN in all states.
SHA256 receipts (semantic excerpts above remain portable if temporary logs disappear):
- RED `a69d19c353424a4f7ba80a74670e8ed9a58bf16661e6291ad8d3185968f32481`.
- GREEN `7935866082307684706dc474250cf2aff0310ce76e53e0bcb32f082aec823516`.
- Compiled-control `10eaaedaddd5b6d16267d13af6cbc22a10c27d0b9bd841146b2edf8cd084008c`.
- Restored-GREEN `f95a76378ec8988eeca835ba97790fa1aeed7a7a8eca3ce5b6f4bcf68545ba0a`.
First `./gradlew :surefold:check :surefold-app:check` FAILED detekt ThrowsCount (5 explicit throws; max3).
That failure remains historical. Root authorised only the independently approved annotation below.
Final `./gradlew spotlessApply`: PASS. Final unfiltered `:surefold:check :surefold-app:check`: PASS (41s).
Core462 tests/0failures/0errors, app131/0/0; typed detekt, NoOtherDependenciesTest and Kover gates PASS.
Final focused restored source after formatting/annotation: PASS28/28; final local reports are GREEN.
Final format log SHA256 `511846c33b9a330089956fae018022e373f598cef9ec6d48533b95ec939ec36f`.
Final check log SHA256 `aeb05d2762de704242931156ba97ec5ba402dd64cf0fa370ee6c354f21429037`.
Final focused log SHA256 `95d9952f8a4b5d3578157b3abd8651a0c7258df3e2fc28d01ad76fb2dbd26498`.
Actual `.github/affected-tasks.sh 35d2c8505dea0d7d0dc96c65402bb687e0a08b58` at source head: `build`.
Core is in surefoldJunitRunsOn, so every module is affected; no smaller full gate claimed.
Full required `./gradlew spotlessApply && ./gradlew build`: UNPAID for correct-head CI, not green or waived.
Root explicitly directed no predictable local full-build hang: actual RedisSuite calls GenericContainer.start(),
other suites use Postgres/Minio/LocalStack; host Docker endpoints were independently unresponsive.
No exclusions, weakened gates, dependency changes or global Docker changes. Kover90 remains required.

## Exception boundaries and remaining work

No catch added/retained in touched test functions; shouldThrow observes then rethrows the SAME signal.
Fail.kt:50 catch(Failed), unchanged: owning scope dispatch, foreign signal rethrow.
Resource.kt:46 catch(Throwable), unchanged: release all, preserve primary identity;
Resource.kt:90 catch(Throwable), unchanged: suppress cleanup onto carried primary. No Either throw/fallback.
Root next: independent SPEC then QUALITY reviews, correct-head CI/full gates, publication/integration/main.
No H acceptance, stable release, B1/B2 adoption, all24/E/S/D, crypto activation, P06/K02 or parent146 closure.
No further build optimisation candidate identified in this bounded change; required checks remain intact.

## Exact independent lint disposition

Report SHA256: `20918f2e964df5ff664dc495a85a5efbc08654d31f95c48935130febd4566d44`.
The complete reviewed report bytes follow (original absolute paths are evidence provenance only).

# Independent Standard review - H ThrowsCount disposition

Decision: **APPROVE the narrow test-local annotation plus one-line reason below.** No policy conflict found; no broader suppression, helper indirection, lint configuration/baseline change or test relaxation is approved.

Reviewed actual working-tree diff against HEAD `35d2c8505dea0d7d0dc96c65402bb687e0a08b58` in `/private/tmp/surefold-p04-h-fail-cleanup-source-20261103`: `Fail.kt`, `ResourceTest.kt`, `FailTest.kt`; committed spec0294; AGENTS.md, CODING_STANDARDS.md, shared detekt policy and build wiring. No edits to that checkout, tests, builds, claims, push or CI were performed. This is a lint disposition, not a complete source SPEC/QUALITY or delivery verdict.

## Exact approved addition

Immediately before the existing `@Test` on `ResourceTest.kt:24–25`, add only:

```kotlin
    // Independent release defects and same-signal observations require five explicit throws.
    @Suppress("ThrowsCount")
```

This adds two lines to the proposed156 changed-test-line packet:158, below the180 reporting target and200 strict stop. No test body/assertion changes are needed or approved by this disposition.

## Policy and necessity

- `AGENTS.md:134–136` identifies a suppression **with no reason** as an invalid response to detekt. The reason here is specific and substantiated by the test body. The file does not forbid reasoned, narrowly scoped suppressions.
- `CODING_STANDARDS.md`, Comments, requires comments to explain a real constraint briefly and applies the same rule to tests. The proposed one-line reason meets that rule. Values/errors guidance permits throws for defects; these tests deliberately exercise throwing cleanup and the opaque Fail boundary rather than converting declared errors into a second model.
- `config/detekt/detekt.yml:141–142` sets ThrowsCount.max=3. The proposed annotation affects only the one test method; the shared rule remains unchanged. `build.gradle.kts:331–348` retains typed detektMain/detektTest on check.
- Existing `FailTest.kt:136–138` uses the same reason-plus-method-suppression pattern for a test whose claim deliberately needs a lint-triggering construct. This is supporting local precedent, not blanket permission.

`ResourceTest.kt:25–81` has exactly five explicit throw sites: outer cleanup defect(:40), inner cleanup defect(:48), initial observed opaque signal(:54), observed inner-boundary signal(:63), observed outer-boundary signal(:76). The cleanup exceptions are distinct identities; all three signal rethrows continue the same unwind through the required observations before the owning attempt consumes it. Assertions verify identity, empty stack, suppression identity/order at intermediate and outer boundaries, release/finally order, Completed exits and unchanged final E identity. Spec0294 Acceptance expressly requires these boundary observations followed by same-signal rethrow. Dropping throws or relocating them merely to evade the count would weaken or obscure that contract. No scope-preserving alternative is needed.

## Evidence and unpaid gates

The recorded original `:surefold:check :surefold-app:check` ThrowsCount failure remains historical evidence and must not be relabelled green. Read handover receipts reporting focused28-test semantic RED, GREEN, compiled false-flag control and exact restored GREEN; this review did not rerun or independently certify those results. The actual annotation/reason still requires ordinary formatting, typed detekt/check and required complete validation after application. Full-build Docker unresponsiveness is a separate unpaid gate and is neither resolved nor waived here. No disablement, exclusion, global threshold change or claim of completed H adoption follows.

## Exact inspected file SHA256

- `AGENTS.md`: `4c874511bd9778bf624eebd59ef5aaf6b6760fa3ca9155cbf15d97c60023d3d4`
- `CODING_STANDARDS.md`: `dff5971fba82adf6babde2b0087cfdb5415132457894ed8049f9536329b8f636`
- `config/detekt/detekt.yml`: `f5321fa45134e96d4a2097a5decd3c511d86d77104caef1ebd0bf4332fe2b730`
- `specs/0294-fail-unwind-cleanup-suppression.md`: `bc85bf2a7db8812755bab7a54908537f9d9f4c014523843bbfedf8c84b94467a`
- `core/surefold/src/main/kotlin/dev/surefold/fail/Fail.kt`: `09221ede5c3aac28e84c5d78278236a72e0ef48440ba49e95cd58fbfa2800148`
- `core/surefold/src/test/kotlin/dev/surefold/ResourceTest.kt`: `99b5181515167f3827f586294f8b0655fc17cc362773723f2116e96eb7c60ee3`
- `core/surefold/src/test/kotlin/dev/surefold/fail/FailTest.kt`: `25e032f9960a7b4a13180543129ac3628b4e2f6cc4dc790cc1786585787f95c4`
