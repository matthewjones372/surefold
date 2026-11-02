# 0281 — Snapshots on GitHub Packages

## Problem

surefold, pelican, kotlin-golden-testing and proofload publish snapshots to Central's snapshot repository from a
`snapshot.yml` dispatched by hand; kimney publishes none. Central drops a snapshot after 90 days, so the snapshots
doorlist pins in its handoffs (`1.2.1-native-decoration-7e6d6124-SNAPSHOT`, `0.17.1-kms-s2-c6d5465d-SNAPSHOT`) stop
resolving within a quarter, and whether snapshots count against the Portal's monthly limits is unknown, which is why
nothing publishes one automatically. The libraries now live in the `surefold` organisation, which has a registry of
its own. Releases stay on Central: this is only about snapshots.

## Not doing

- Not moving releases, signing or the Central namespace (`dev.surefold`).
- Not changing how a snapshot version is named (`<next>-SNAPSHOT`, or a branch's `<next>-<label>-SNAPSHOT`).
- Not deleting snapshots already on Central; they expire on their own.
- Not migrating doorlist in this spec: it changes its repositories after its merge pause, in its own change.

## Shape

Each library publishes to its own repository's Maven registry, `https://maven.pkg.github.com/surefold/<repo>`, with the
workflow's `GITHUB_TOKEN` (`permissions: packages: write`). `snapshot.yml` runs on every push to `main` and stays
dispatchable from a branch for a labelled snapshot.

```kotlin
// a library's build: one more repository beside Central's
publishing {
    repositories {
        maven("https://maven.pkg.github.com/surefold/surefold") {
            name = "githubPackages"
            credentials(PasswordCredentials::class) // ORG_GRADLE_PROJECT_githubPackagesUsername / …Password
        }
    }
}

// a consumer's settings.gradle.kts: only for a -SNAPSHOT, and only these groups
maven("https://maven.pkg.github.com/surefold/surefold") {
    credentials(PasswordCredentials::class) // gpr.user / gpr.key, or env in CI
    content { includeGroup("dev.surefold") }
    mavenContent { snapshotsOnly() }
}
```

GitHub Packages needs a token to read, even a public package, and a Maven package's permissions are its repository's,
so a workflow in another repository cannot read it with its own `GITHUB_TOKEN`. Consumers' CI reads with an
organisation secret `PACKAGES_READ_TOKEN`: a classic PAT with only `read:packages`. People put the same kind of PAT in
`~/.gradle/gradle.properties` as `gpr.user`/`gpr.key`.

## Why this shape

A snapshot that never expires is what a pinned handoff needs, and public packages cost nothing to store or download,
including from Actions. The price is the read token: today anyone can try a snapshot with one repository line; after
this, they need a PAT. The alternative that keeps anonymous reads is a static Maven repository on a public bucket
(Cloudflare R2 behind a custom domain such as `maven.surefold.dev`): no read token, but a bucket, a write credential
and a domain to run. Recommended: GitHub Packages, since nobody outside the organisation consumes snapshots today.

## Stack

- [ ] **`snapshots-github-packages`** (this repo) — the `githubPackages` repository and `snapshot.yml` publishing to it
      on every push to `main`; the README's snapshot paragraph names the new repository and the token.
      Done when: a push to `main` lists `dev.surefold:surefold` under the repository's Packages.
- [ ] The same entry in pelican, kimney (new `snapshot.yml`), kotlin-golden-testing and proofload, one PR each.
      Done when: each repository's Packages list its modules at the `main` snapshot.
- [ ] **`snapshots-readers`** in petshop and pelican (Gannet stays on Central until Gannet moves): the repositories
      above, credentials from `PACKAGES_READ_TOKEN`. Done when: their CI resolves a snapshot from GitHub Packages.

## Acceptance

```bash
./gradlew publishAllPublicationsToGithubPackagesRepository   # in CI, from main
curl -sf -u "$USER:$PAT" https://maven.pkg.github.com/surefold/surefold/dev/surefold/surefold/maven-metadata.xml
```

## Open questions

1. Every push to `main`, or by hand as now? Recommended: every push; it is free for public packages and removes the
   "which snapshot is current" question.
2. Who owns the read PAT: a person's account or a machine account in the organisation? Recommended: a machine
   account, so the secret survives one person leaving and its scope stays `read:packages` only.
3. Publish to Central's snapshot repository as well, for one release, so external readers have time to move?
   Recommended: no, nobody outside the organisation reads snapshots today.
4. kotlin-golden-testing and proofload are still in `matthewjones372`: move them into the organisation first, or
   publish to `maven.pkg.github.com/matthewjones372/<repo>`? Recommended: move them first, so every snapshot
   repository is under `surefold/` and one organisation secret reads them all.
5. Keep every snapshot version forever, or prune to the last N with `actions/delete-package-versions`?
   Recommended: keep all; storage for public packages is free and pinned handoffs are the point.
