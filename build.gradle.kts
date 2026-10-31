plugins {
    kotlin("jvm") version "2.4.10" apply false
    // @Serializable classes: surefold-actor-remote-kotlinx's tests, and the services that use it (spec 0093).
    kotlin("plugin.serialization") version "2.4.10" apply false
    // A failed `expect` in surefold-junit's suites draws its values (spec 0175).
    kotlin("plugin.power-assert") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published module ships.
    id("org.jetbrains.dokka") version "2.1.0" apply false
}

scmVersion {
    tag { prefix.set("v") }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name. The README tells a contributor to
    // install locally and depend on the version; that version should not
    // change with the branch they happen to be on.
    versionCreator("simple")
}

// Read once, at configuration time: `scmVersion.version` shells out to git.
val scmVer: String = scmVersion.version

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is hand-laid-out
 * on purpose — banner comments, aligned trailing comments, KDoc written as
 * prose. The rules that make ktlint disagree with any of that are turned off
 * in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.8.0"

/**
 * The Kotlin this build compiles with, for the two modules that compile against the compiler itself.
 *
 * Written once: a plugin built against one compiler and loaded into another fails at a consumer's
 * compile, or quietly in their editor, and a version number repeated in three build scripts is three
 * chances for that to happen on a bump.
 */
val kotlinVersion: String = "2.4.10"

// Spotless does not pick these up from .editorconfig for every source set, so
// they are handed to the ktlint step directly. The reasoning for each lives in
// .editorconfig beside the rest, which is also what the IDE reads.
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    kotlinGradle {
        // settings.gradle.kts and this file. The module formats its own build
        // script in the `subprojects` block below.
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "surefold" to
        "Structured concurrency on virtual threads, without coroutines. " +
        "Plus checked application graphs, streams, actors and clusters.",
    "surefold-pekko" to
        "surefold on Pekko: a dispatcher as the executor, and Pekko's stages awaited from a virtual thread.",
    "surefold-stream" to "A stream that names its failure: Stream<E, A> described once, run on the backend you pick.",
    "surefold-stream-pekko" to
        "surefold-stream on Pekko Streams: the backend, and the operators that take Pekko's types.",
    "surefold-stream-forks" to "surefold-stream on surefold's own forks: a run is a pull loop on a virtual thread.",
    "surefold-stream-actors" to
        "surefold-stream on surefold-actor: a run is an actor, pulling its loop a batch at a time.",
    "surefold-stream-test" to "surefold-stream for tests: every stage on the calling thread, on a clock the test owns.",
    "surefold-arrow" to
        "Surefold's Either, NonEmptyList and Fail to and from Arrow's, for a codebase already on Arrow.",
    "surefold-assert" to
        "Checks for tests of surefold code that fail at the test's own line, Either matchers among them.",
    "surefold-test" to
        "For tests of a surefold service: eventually on surefold's clock, and a test that reads as a story.",
    "surefold-junit" to "Surefold's tests on the JUnit Platform: a suite is an object, its tests share one resource.",
    "surefold-junit-testkit" to
        "Checks on a suite's run, read from EngineTestKit: which tests passed, failed or were skipped.",
    "surefold-app" to "An application as a value: a dependency graph that validates, subsets and starts itself.",
    "surefold-actor-pekko" to
        "A Pekko typed actor from Kotlin: ask one on a virtual thread, without the wiring graph.",
    "surefold-app-pekko" to "surefold-app on Pekko: an actor is a node, spawned in order and stopped in reverse.",
    "surefold-otel" to "surefold on OpenTelemetry: a Context that crosses a fork, so a trace survives a parMap.",
    "surefold-jdbc-otel" to "JDBC tracing with automatic query names and protective OpenTelemetry metadata defaults.",
    "surefold-jdbc-otel-bench" to "The reproducible JDBC tracing workload and telemetry pipeline for spec 0266.",
    "surefold-otel-test" to
        "For tests of a traced service: a tracer that keeps the spans it ends, beside surefold-otel's storage.",
    "surefold-slf4j" to "surefold's log lines through SLF4J, with a fork's annotations in the MDC.",
    "surefold-micrometer" to "surefold's metrics into a Micrometer registry.",
    "surefold-app-liquibase" to
        "surefold-app on Liquibase: a migration is a node, and reading the database depends on it.",
    "surefold-app-flyway" to
        "surefold-app on Flyway: SQL migrations as a node, with the settings a production database wants pinned.",
    "surefold-app-typesafe" to
        "surefold-app on Typesafe Config: a section is a node, and a bad file says every fault at once.",
    "surefold-app-kotlinx" to
        "surefold-app-typesafe from a @Serializable class: the section read from the class itself.",
    "surefold-app-gradle" to "surefold-app as a build gate: every graph in a project checked and drawn as it compiles.",
    "surefold-app-compiler" to
        "surefold-app in the compiler: a K2 checker, so the IDE reports a graph's faults as you type.",
    "surefold-cache" to
        "A cache with its lookup fixed when it is made: one lookup per key, a ttl by result, stale on failure.",
    "surefold-replay" to
        "surefold forks in a seeded order: explore finds the order that breaks a test, replay runs it again.",
    "surefold-compiler" to
        "surefold's structure rules in the compiler: a Deferred that outlives its scope fails to compile.",
    "surefold-structured" to "surefold over the JDK's StructuredTaskScope: a scope the thread dump can see.",
    "surefold-kafka" to
        "Kafka as a surefold Stream on any backend: a record is committed only after the work on it is done.",
    "surefold-kafka-pekko" to
        "surefold-kafka through Pekko's own Kafka connector: prefetch, batched commits, a draining stop.",
    "surefold-kafka-test" to
        "A Kafka broker in the test JVM for a service's own tests: no Docker, and helpers to read what it did.",
    "surefold-actor" to
        "An actor as a value and a step, on virtual threads: no actor system, and tests that never wait.",
    "surefold-actor-journal-jdbc" to
        "surefold-actor's journal and snapshots on JDBC: events every node reaches, two writers settled by the key.",
    "surefold-actor-projection" to
        "surefold-actor's journal as a surefold Stream: a read model follows one kind, and resumes where it left off.",
    "surefold-actor-remote" to "surefold-actor across nodes: codecs you own, and one TCP connection per pair of nodes.",
    "surefold-actor-remote-avro" to
        "surefold-actor-remote's messages in Avro: specific records, resolved across versions, and asks.",
    "surefold-actor-remote-protobuf" to
        "surefold-actor-remote's messages in Protobuf: generated messages, protocols of them, and asks.",
    "surefold-actor-remote-kotlinx" to
        "surefold-actor-remote's messages, events and snapshots from @Serializable data classes, " +
        "in ProtoBuf by default.",
    "surefold-cluster" to "surefold-actor as a cluster: discovery, gossip membership and downing, with no coordinator.",
    "surefold-cluster-aws" to
        "surefold-cluster on AWS: seeds from Cloud Map or ECS, and a DynamoDB lease to break an even split.",
    "surefold-cluster-kubernetes" to
        "surefold-cluster on Kubernetes: seeds from the pods API, and a Lease to break an even split.",
    "surefold-app-actor" to
        "surefold-app on surefold-actor: a scope is a node, and an actor is a node keyed by its protocol.",
    "surefold-app-cluster" to "surefold-app on surefold-cluster: a cluster is a node, joined as a HOCON section says.",
    "surefold-openfeature" to
        "surefold on OpenFeature: who a flag is evaluated for, bound once and read in every fork and actor.",
    "surefold-app-openfeature" to
        "surefold-app on OpenFeature: the provider is a node, set before any flag is read and shut down on release.",
    "surefold-app-openfeature-flagd" to
        "surefold-app-openfeature on flagd: flags in flagd's schema as a section of the service's own HOCON.",
    "surefold-jobs" to
        "Durable jobs on Postgres, enqueued in the caller's own transaction so they commit with its change.",
    "surefold-app-jobs" to
        "surefold-app on surefold-jobs: a worker is a node, started after the migrations and drained on release.",
    "surefold-jobs-otel" to
        "surefold-jobs on OpenTelemetry: a span per run of a job, linked to the span that enqueued it.",
    "surefold-files" to
        "Files on any S3-compatible store: put, get, list and presigned URLs, typed failures, and a fake for tests.",
    "surefold-files-test" to
        "MinIO in Testcontainers for a service's own tests of surefold-files: a fresh bucket a call.",
    "surefold-tenancy" to
        "Tenancy Postgres enforces: a tenant forks inherit, row-level security per transaction, and an audited bypass.",
    "surefold-tenancy-test" to "A check that a service's tables keep two tenants apart under row-level security.",
    "surefold-jobs-tenancy" to
        "surefold-jobs on surefold-tenancy: each job's handler runs as the tenant its row names.",
    "surefold-idempotency" to
        "Idempotent requests on Postgres: keys reserved atomically across instances, and answers replayed.",
    "surefold-ratelimit" to "Rate limits as token buckets, in memory or on Postgres, shared by every instance.",
    "surefold-tenancy-crypto" to
        "A key per tenant, rooted in a KMS key no backup holds: delete the root and every copy is unreadable.",
    "surefold-tenancy-crypto-aws" to
        "surefold-tenancy-crypto on AWS KMS: one customer managed key per tenant, found by alias.",
    "surefold-openfeature-postgres" to
        "Feature flags per tenant from a Postgres table, read under its row-level security policy, for OpenFeature.",
    "surefold-cache-redis" to
        "surefold-cache on Redis: a layer every instance shares, and invalidation over Redis pub/sub.",
    "surefold-cache-tenancy" to
        "A surefold-cache whose every entry is one tenant's, looked up as that tenant, and refused with no tenant.",
    "surefold-cache-postgres" to
        "A surefold-cache told by Postgres NOTIFY that a row changed: a payload drops its key, a gap every key.",
    "surefold-postgres-notify" to
        "Postgres's LISTEN and NOTIFY as a surefold Stream: one connection, notifications in commit order.",
    "surefold-auth" to
        "Sessions and credentials on Postgres: Argon2id passwords, rotating sessions, magic links, TOTP, roles.",
    "surefold-auth-oidc" to
        "OpenID Connect sign-in on Nimbus: the code flow with PKCE, and each ID token check its own error.",
)

// A Gradle plugin publishes through `java-gradle-plugin`'s own marker publication, and what it does
// is a build that runs rather than a line count: petshop applying it is the test, not a percentage.
val gradlePluginModules = setOf("surefold-app-gradle")

// The same argument for the compiler plugin: it runs inside the Kotlin compiler, so the only honest
// test of it is a compilation, and those live in surefold-app-gradle where the build that runs them is.
val testedByRunningABuild = gradlePluginModules + "surefold-app-compiler"

// The other compiler plugin, whose tests compile snippets in their own JVM: on 21 with the build tools, and on
// Jupiter, as the plugins beside it are.
val compilerPlugins = setOf("surefold-app-compiler", "surefold-compiler")

// surefold-junit and what it runs on keep their tests on Jupiter: a runner cannot report on its own parts (spec 0197).
val surefoldJunitRunsOn =
    setOf("surefold", "surefold-assert", "surefold-test", "surefold-junit", "surefold-junit-testkit")

// Measured, not tested: a benchmark has no assertions for a coverage floor to count.
val benchmarkModules =
    setOf(
        "surefold-stream-benchmarks", "surefold-actor-benchmarks", "surefold-kafka-benchmarks",
        "surefold-jobs-load", "surefold-jdbc-otel-bench",
    )

// Built and tested, never published: surefold-structured calls StructuredTaskScope, a preview API until
// JDK 28 (JEP 543), and a release must not promise an API the JDK has not. surefold-bank is an application
// to run, not a library to depend on (spec 0094).
val unpublished =
    setOf("surefold-structured", "surefold-stream-parity", "surefold-bank") + benchmarkModules

// What llms.txt and the surefold skill must name, no more and no fewer: AgentDocsSuite in surefold-app reads it.
extra["publishedModules"] = subprojects.map { it.name }.filterNot { it in unpublished }.sorted()

// AgentDocsSuite holds this to the published set: a module missing here ships a POM with no description.
extra["describedModules"] = moduleDescriptions.keys.sorted()

// The floor is a ratchet against regression, not a target to code towards — a
// test written to move a percentage is worth less than no test at all.
kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}

dependencies {
    subprojects.filterNot { it.name in testedByRunningABuild + benchmarkModules }.forEach { kover(project(it.path)) }
}

// A floor nobody runs is not a floor: `./gradlew build` checks it.
tasks.named("check") {
    dependsOn("koverVerify")
}

extra["surefoldKotlinVersion"] = kotlinVersion

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }

    // The namespace verified on the Central Portal, against the GitHub
    // account it names.
    group = "io.github.matthewjones372"
    version = scmVer

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        // 25: the first LTS where a virtual thread blocked in `synchronized` no longer pins its carrier, which
        // every library here runs on. What runs inside the build tools stays at 21, the JDK a Gradle daemon or a
        // Kotlin daemon may still be on: the Gradle plugin and the compiler plugin it registers.
        jvmToolchain(if (project.name in gradlePluginModules + compilerPlugins) 21 else 25)

        compilerOptions {
            // A warning nobody reads is a defect nobody fixed; the build says so.
            allWarningsAsErrors.set(true)
            // Without this, every interface with a method body also gets a
            // `DefaultImpls` class holding a copy of it, and both are published
            // surface the BCV gate then has to keep. The interfaces here
            // already emit real JVM default methods, so the copies are the ABI
            // of a compatibility mode with nothing behind it: Kotlin callers
            // never reach them, and the Java caller they exist for would have
            // had to write `JsonValue.DefaultImpls.x(this)` by hand.
            jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // A module's tests are surefold-junit suites (spec 0197), and a failed `expect` draws its values. Not for what
    // surefold-junit is built on, which a bug would break the runner reporting it, nor for the JDK 21 build tools.
    if (name !in surefoldJunitRunsOn + testedByRunningABuild + compilerPlugins + benchmarkModules) {
        apply(plugin = "org.jetbrains.kotlin.plugin.power-assert")
        dependencies { "testImplementation"(project(":surefold-junit")) }
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        extensions.configure<org.jetbrains.kotlin.powerassert.gradle.PowerAssertGradleExtension> {
            functions.add("io.github.matthewjones372.surefold.junit.expect")
            includedSourceSets.add("test")
        }
        // The checker reports the standard library's dropped must-use values, and only under it does `val _ =` compile.
        tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
            compilerOptions.freeCompilerArgs.add("-Xreturn-value-checker=check")
        }
    }

    tasks.withType<Test>().configureEach {
        // `-PincludeTags=io,db` runs only the tests tagged so: Jupiter's @Tag and surefold-junit's tag() alike.
        val included = providers.gradleProperty("includeTags").orNull?.split(',')?.filter { it.isNotBlank() }
        useJUnitPlatform { included?.let { includeTags(*it.toTypedArray()) } }

        systemProperty("junit.jupiter.execution.timeout.default", "60s")
        // The same default for surefold-junit suites, which Jupiter's property does not reach (spec 0193).
        systemProperty("surefold.junit.timeout.default", "60s")
    }

    if (name !in testedByRunningABuild + benchmarkModules) {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }

    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // The deviations live in one file for the whole build; see its header.
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    // The plain `detekt` task cannot see types, so the rules that need them —
    // ForbiddenMethodCall, for one — are silently skipped there. `check`
    // depends on the type-resolving pair instead.
    tasks.named("check") { dependsOn("detektMain", "detektTest") }
    // A source detekt cannot compile is analysed without its types, and detekt
    // only prints a count of the errors and passes: it has no setting to fail
    // instead, so each task watches its own output. Detekt runs on the task's
    // thread, and other tasks print to the same listeners in parallel, so only
    // that thread's lines count. The usual cause is a call to code a compiler
    // plugin generates, such as `Paid.serializer()`: detekt runs no compiler
    // plugins, and `serializer<Paid>()` resolves without one.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        val running = java.util.concurrent.atomic.AtomicReference<Thread>()
        val compilerErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        doFirst { running.set(Thread.currentThread()) }
        logging.addStandardOutputListener {
            if (Thread.currentThread() == running.get() && "compiler errors found during analysis" in it) {
                compilerErrors += it.toString().trim()
            }
        }
        doLast {
            if (compilerErrors.isNotEmpty()) {
                throw GradleException(
                    "$path: ${compilerErrors.joinToString()} Set `debug = true` on the task to see them.",
                )
            }
        }
    }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    if (name in unpublished) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // Sources are not an optional extra for a library someone else has to
        // debug, and Maven Central will not accept a release without a javadoc
        // jar. Dokka fills it: an empty jar leaves javadoc.io blank, which puts
        // the KDoc out of reach of anyone who has not cloned the repository.
        // A Gradle plugin says which platform it is in its own build script, where
        // `java-gradle-plugin` has been applied: this block runs before that.
        if (name !in gradlePluginModules) {
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                    sourcesJar = true,
                ),
            )
        }

        pom {
            name.set(this@subprojects.name)
            description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Surefold.")
            url.set("https://github.com/matthewjones372/lark")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("matthewjones372")
                    name.set("Matt Jones")
                }
            }
            scm {
                url.set("https://github.com/matthewjones372/lark")
                connection.set("scm:git:https://github.com/matthewjones372/lark.git")
                developerConnection.set("scm:git:ssh://git@github.com/matthewjones372/lark.git")
            }
        }
    }

    // A tag gives axion a version with no `-SNAPSHOT`, which makes Gradle's signing
    // required — and `build` signs the publications, so the gate starts depending on
    // a key. The release job has one; the gate build and every pull request do not,
    // and handing them the signing key to run a task nobody publishes from would be
    // the wrong way round. Signed where there is something to sign with, skipped
    // where there is not, and `publishToMavenCentral` still fails loudly on an
    // unsigned artifact.
    tasks.withType<Sign>().configureEach {
        onlyIf { !(project.findProperty("signingInMemoryKey") as? String).isNullOrBlank() }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // `./gradlew publishToMavenLocal` for a local try-out, and
            // `publishAllPublicationsToLocalRepository` for something to
            // inspect without installing it.
            maven {
                name = "local"
                url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
            }
        }
    }
}
