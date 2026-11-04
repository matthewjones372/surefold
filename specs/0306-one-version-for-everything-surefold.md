# 0306 — One version for everything Surefold

## Problem

After the split (0301, 0302) each repository has its own version (0301 question 3). A service that takes core
0.21.0 directly and `surefold-jobs` 0.20.0, which was built and tested against core 0.19.0, gets core 0.21.0 from
Gradle (newest wins) or whichever is nearest from Maven, and nothing fails until a `NoSuchMethodError` or a changed
behaviour at runtime. Surefold's API changes between releases, so any mix nobody tested is a bug waiting. Today the
only defence is the user picking three versions that happen to agree.

## Not doing

- **Lockstep versions.** The repositories keep releasing on their own.
- **Version ranges** in any Surefold POM. A library pins exactly what it was tested with.
- **Compatibility across versions.** The BOM names one tested set; it doesn't promise any other set works.
- **Publishing before the first release.** Until then the BOM is published as a snapshot like everything else.

## Shape

`surefold-platform` publishes `dev.surefold:surefold-bom`, a Gradle `java-platform` (and Maven BOM) with a calendar
version, in the same release as its own modules:

```kotlin
dependencies {
    implementation(platform("dev.surefold:surefold-bom:2026.11.0"))
    implementation("dev.surefold:surefold")
    implementation("dev.surefold:surefold-otel")
    implementation("dev.surefold:surefold-jobs")
}
```

- **It lists every published module of all three repositories**, at exactly the core and integrations versions
  `surefold-platform` pins in `gradle.properties` and builds against, and platform's own version for its modules.
- **The set it names is the set that was tested**: platform's build, against those pins, is what the release runs.
  A BOM entry for a module no test in that build depends on is a build failure.
- **`enforcedPlatform(...)`** is documented for a service that wants Gradle to refuse anything else; `platform(...)`
  stays the default, so a deliberate direct version still wins.
- **Each repository's POMs depend on exact upstream versions**, never ranges, and `surefold-integrations` publishes
  nothing of its own about which platform it suits.
- **Docs:** `docs/modules.md` in each repository shows the BOM first, and the README's dependency snippet uses it.
- **A user of core alone** can still depend on `dev.surefold:surefold:<version>` with no BOM.

## Why this shape

Platform is the furthest downstream, so it is the only repository that already builds against one version of each
of the others: its pins are the tested set, and the BOM just publishes them. **Alternative:** a fourth repository
that only holds the BOM. It decouples the BOM's releases, but it then needs its own build that tests the set, which
is platform's build again. **Alternative:** one shared version and Gradle's virtual-platform alignment. It needs no
BOM, but every release bumps all three repositories, the coupling 0302 split them to avoid. Recommended
(maintainer, 2026-11-04): a BOM from `surefold-platform`.

## Stack

- [ ] **`spec-0306-bom`** (surefold-platform) — the `surefold-bom` `java-platform` project, generated from the pins
      in `gradle.properties` and the list of published modules, published with every snapshot.
      Done when: a project outside the build resolves core, an integration and a platform module through the BOM
      with no versions, and gets exactly the pinned ones.
- [ ] **`spec-0306-covered`** (surefold-platform) — a check that every BOM entry is a module some test in the build
      depends on, and that no POM in any repository uses a range.
      Done when: adding an untested module to the BOM, or a range to a POM, fails `./gradlew build`.
- [ ] **`spec-0306-docs`** (all three) — `docs/modules.md` and the README show the BOM first.
      Done when: the snippets compile in each repository's guide test.

## Acceptance

```bash
./gradlew build                                    # in surefold-platform
./gradlew -p bom-consumer dependencies             # a throwaway consumer resolves the pinned versions
```

## Open questions

1. **Calendar version or the platform's own version for the BOM?** Answered (maintainer, 2026-11-04), as recommended: calendar (`2026.11.0`), so it never
   reads as one repository's version when it names three.
2. **Does core's README show the BOM, though core doesn't publish it?** Answered (maintainer, 2026-11-04), as recommended: yes, as the default way in, with
   the plain core coordinate next to it for core-only users.
3. **Ship the BOM from the first snapshot after the split, before any Central release?** Answered (maintainer, 2026-11-04), as recommended: yes, so the
   way users will depend on Surefold is what the proving period exercises.
