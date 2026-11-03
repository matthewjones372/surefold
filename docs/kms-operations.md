# Explicit AWS KMS operations

From 0.18.0 (specs [0260](../specs/0260-explicit-kms-operations.md) and
[0261](../specs/0261-aws-explicit-kms-operations.md)), in `dev.surefold:surefold-tenancy-crypto-aws:0.18.0`.
Existing `Kms` and `AwsKms` remain unchanged.

Construct `AwsKeyOperations(clients, creationClients, preferredDataRegion)` and use it as `KeyOperations`.
Both maps are snapshotted; clients remain caller owned. Map keys must match each client's configured Region.
For library-owned clients, bind `awsKeyOperations(regions, credentialsProvider, preferredDataRegion)` into a
Surefold `ResourceScope`, or use Surefold's `use`. It installs separate normal (at most three attempts) and
creation (one attempt) clients per Region, with UrlConnectionHttpClient. Scope exit closes every acquired
client in reverse order, including partial acquisition failure and when another close throws.

Pass an opaque caller-owned SDK `AwsCredentialsProvider`. The SDK still resolves and refreshes credentials;
the adapter does not cache credentials. SDK 2.55.6 closes configured closeable attributes, so a nonclosing
provider delegate preserves both credential and identity resolution. Close the provider only after all its
client scopes end. If using the default chain, own `DefaultCredentialsProvider.builder().build()` in an outer
scope; do not take ownership of the shared `DefaultCredentialsProvider.create()` singleton.

The application selects separate runtime, provisioner and deletion role providers and operation instances.
The library neither assumes STS roles nor reads SSM. `creationClients` separates retry settings; it grants
no additional IAM authority. Borrowed creation clients must expose a verified SDK retry strategy with one
maximum attempt. Default or uninspectable retry configuration is refused before creation.

Supply canonical key ARNs and an `EncryptionContext` containing exactly the stored context entries.
For example, a legacy platform context can be `tenant_id=platform`, `purpose=enc`, `version=1`; use it only
when those are the actual stored values. Generate returns AES-256 material; wrap accepts existing bytes.
Unwrap requires the same root identity and exact context. Erase caller-owned plaintext arrays after use.
Never log contexts, policies, plaintext or provider errors; failures contain only operation and closed code.

With `preferredDataRegion=null`, data calls retain their supplied Region. Selecting a Region routes **generate,
wrap and unwrap** through verified local MRK metadata without contacting the primary; it checks logical key id,
account, partition and relationship. Keep the stored primary ARN. An ARN rewrite alone is insufficient.
Management, ownership/tag checks and deletion always use their explicit locations. For startup discovery use
`resolveAlias(configuredRegion, configuredAlias)` locally. Discovery never provisions a root or moves an alias.

Checkpoint create-primary, alias binding, rotation and replica creation separately in the caller's durable
journal. `Creating` means the replica creation stage was accepted; `inspectReplica` must confirm `Enabled`
before readiness. A lost reply remains `UncertainMutation`: single attempt is **not exactly once**. Stop and
reconcile roots/tags and the journal before another creation. A conflicting alias is never reassigned.
Deletion needs the full expected regional topology, required tag subsets and explicit regional authority;
replicas are scheduled before primary. Aliases remain. Preserve regional partial progress and provider dates;
a primary awaiting replica deletion can have an unknown date. Missing discovery never proves destruction.
The application must finish and review its durable creation journal and creator/rollback fencing before its
management cutover; publishing this adapter does not satisfy that gate.

## Verification before stable release

Local protocol tests prove requests, context preservation, bounded retries, uncertain creation, pagination,
redaction and ownership. They cannot prove actual regional behavior, IAM policies or least privilege.
Run the full acceptance commands in spec 0261 and review the unchanged legacy source/class hash evidence.
The harness below is test source compiled by ordinary builds and executed **only** by `kmsRegionalProof`.
Use a dedicated approved account and role-specific profiles, two enabled Regions, unique proof id, explicit
regional policy files and disposable roots. Policies, credentials and contexts must never enter the evidence log.
Complete the negative permission checks below before running the final delete command.
The commands use files `/private/tmp/kms-primary-policy.json` and `/private/tmp/kms-replica-policy.json` prepared
and reviewed by the account owner, granting the intended role capabilities. They are not generated policies.

```bash
export PROOF_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
AWS_PROFILE=proof-provisioner ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=create-primary --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --policy=/private/tmp/kms-primary-policy.json --mutate"
# Set PRIMARY_ARN to the root ARN printed above, retaining it with this proof id.
export PRIMARY_ARN='arn:aws:kms:eu-west-2:123456789012:key/mrk-replace-with-observed-id'
AWS_PROFILE=proof-provisioner ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=prepare --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN --alias=alias/surefold-proof/$PROOF_ID --mutate"
AWS_PROFILE=proof-provisioner ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=replicate --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN --replica-region=eu-west-1 --policy=/private/tmp/kms-replica-policy.json --mutate"
# Retain REPLICA_ARN from the replicate reply with PRIMARY_ARN and PROOF_ID; do not derive history from later topology.
export REPLICA_ARN='arn:aws:kms:eu-west-1:123456789012:key/mrk-replace-with-observed-id'
AWS_PROFILE=proof-provisioner ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=inspect --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN"
AWS_PROFILE=proof-runtime ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=crypto --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN"
AWS_PROFILE=proof-runtime ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=crypto --regions=eu-west-1 --data-region=eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN"
AWS_PROFILE=proof-deletion ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=delete --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN --mutate"
AWS_PROFILE=proof-deletion ./gradlew :surefold-tenancy-crypto-aws:kmsRegionalProof -PkmsProofArgs="--stage=observe --regions=eu-west-2,eu-west-1 --proof=$PROOF_ID --root=$PRIMARY_ARN --replica-root=$REPLICA_ARN"
```

Each invocation performs one stage, prints only root IDs/states/closed failures, and fails with a sanitised exception
on uncertainty or invalid evidence. `observe` reads both explicitly recorded ARNs independently, including pending
states and absent current relationships. It visits the full inventory; `NotObserved` remains unresolved evidence.
There is no polling, automatic resubmission or production journal. After `Creating`, run **inspect only** later
until enabled; never repeat a creation because a reply was lost. Inspect full regional keys/tags and reconcile
with the approved proof record first. The literal example ARN must be replaced with the observed ARN.
Mutation stages require `--mutate`; all existing roots must carry the exact proof id and disposable tags.
The local-data command deliberately omits a primary client. Crypto checks exact-context round-trip and changed
context refusal, erasing plaintext afterwards. Record safe CloudTrail event IDs to corroborate the Region used.

Before deletion, repeat the crypto stage under an approved negative-test runtime profile with wrap/unwrap
permissions removed; record the closed refusal. Verify that runtime cannot create, replicate, rotate or delete
with IAM policy simulation and reviewed CloudTrail denials on these disposable roots only. Deliberately remove
one deletion Region's permission, run delete once, retain its partial progress, restore authority, inspect all
regions and reconcile before continuing. Record replica-first scheduling, retained alias binding, pending states
and actual or unknown dates. After the seven-day window, inspect every previously recorded root directly in
both Regions using `observe`; neither `NotObserved` nor absence from a list proves destruction. Readiness-only
`inspect` still requires enabled replicas. Never treat a local test as this real AWS evidence.

Only after reviewed regional/IAM evidence: merge through normal review, run final build checks on that commit,
select the approved version and create its `vX.Y.Z` release tag. The existing release CI stages artifacts in the
Central Portal; an owner inspects and releases that deployment. Do not publish from this proof task. Record the
released module versions, Git commit, successful CI run, Portal deployment and SHA-256 of each downloaded JAR
(`shasum -a 256 path/to/downloaded.jar`), then verify a consumer can resolve exactly those bytes. Stable publication
and application activation are separate approvals; the application journal/fencing gate remains in force.
