# 0263 — AWS credentials borrowed by SDK clients

## Problem

The AWS SDK closes configured providers that implement AutoCloseable. An application
sharing one refreshing provider across KMS and SSM clients needs client closure to
leave that provider alive until its owning Resource releases it. Spec 0261 already
has a private identity-forwarding view; callers cannot reuse it for other clients.

## Not doing

No credential storage, role selection, refresh implementation, new dependency or
module. No KMS operation, retry policy, mutation behavior or existing branch rewrite.

## Shape

Add this public function in surefold-tenancy-crypto-aws and reuse it in the existing
owned key-operations Resource factory:

```kotlin
import dev.surefold.tenancy.crypto.aws.borrowedAwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

fun clientCredentials(owner: AwsCredentialsProvider): AwsCredentialsProvider =
    borrowedAwsCredentialsProvider(owner)
```

Creation resolves no identity. Each resolveCredentials call forwards to the owner;
identityType and resolveIdentity forward unchanged, including future identity,
failure and refresh semantics. The view is not AutoCloseable and caches no value.
Closing actual SDK clients configured with it leaves the owner open. The caller
releases the owner only after every borrowing client. Existing APIs stay compatible.

## Why this shape

Expose the already-tested private ownership boundary rather than copying it into
each application. A new multi-service client factory would also share credentials,
but would mix client configuration and ownership policy into a broader API.

Implementation base: fresh #948 tip87c19ff2. The spec lands on main first. Its true two-tip difference from the consumer's pinned
2f34332c contains only documentation; all production/test/build/spec source matches.
No source correction needs porting. Existing feature branches remain unchanged.

## Stack

- [x] **`codex/kms-borrowed-credentials-main-spec`** — this additive contract only, on main.
      Done when: independently reviewed spec merged to main; SpecsSuite/main CI green.
- [x] **`codex/kms-borrowed-credentials`** — on87c with the approved main spec: expose/reuse
      the view, tests and module docs.
      Done when: lazy forwarding, same identity/failure, refreshing values and actual
      SDK client-close retention tests pass; existing operations/resources stay green.

## Acceptance

Write failing tests before implementation. Run focused AWS suites and affected
checks, then formatting and the required build/CI gates without weakening checks.

```bash
./gradlew :surefold-app:test --tests '*SpecsSuite'
./gradlew :surefold-tenancy-crypto-aws:test
./gradlew spotlessApply
./gradlew build
```

## Open questions

Resolved by independent director technical review under the consumer owner delegation,
30 October 2026: borrowedAwsCredentialsProvider is public in the existing AWS module.
The two-entry additive stack and exact forwarding/ownership contract are approved. A new
unique source-pinned development snapshot may follow review; stable publication
waits for the consumer R00 release gate. Normal integration of S1/S2/helper with
current main and complete release evidence remains required; no rebasing or force.
