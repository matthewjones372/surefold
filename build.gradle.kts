plugins {
    kotlin("jvm") version "2.4.10" apply false
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
    "surefold-stream" to "A stream that names its failure: Stream<E, A> described once, run on the backend you pick.",
    "surefold-stream-forks" to "surefold-stream on surefold's own forks: a run is a pull loop on a virtual thread.",
    "surefold-stream-actors" to
        "surefold-stream on surefold-actor: a run is an actor, pulling its loop a batch at a time.",
    "surefold-stream-test" to "surefold-stream for tests: every stage on the calling thread, on a clock the test owns.",
    "surefold-assert" to
        "Checks for tests of surefold code that fail at the test's own line, Either matchers among them.",
    "surefold-test" to
        "For tests of a surefold service: eventually on surefold's clock, and a test that reads as a story.",
    "surefold-junit" to "Surefold's tests on the JUnit Platform: a suite is an object, its tests share one resource.",
    "surefold-junit-testkit" to
        "Checks on a suite's run, read from EngineTestKit: which tests passed, failed or were skipped.",
    "surefold-app" to "An application as a value: a dependency graph that validates, subsets and starts itself.",
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
    "surefold-actor" to
        "An actor as a value and a step, on virtual threads: no actor system, and tests that never wait.",
    "surefold-actor-journal-jdbc" to
        "surefold-actor's journal and snapshots on JDBC: events every node reaches, two writers settled by the key.",
    "surefold-actor-projection" to
        "surefold-actor's journal as a surefold Stream: a read model follows one kind, and resumes where it left off.",
    "surefold-actor-remote" to "surefold-actor across nodes: codecs you own, and one TCP connection per pair of nodes.",
    "surefold-cluster" to "surefold-actor as a cluster: discovery, gossip membership and downing, with no coordinator.",
    "surefold-cluster-saga" to "Durable sagas: plain step code journalled and replayed, so a saga outlives its node.",
    "surefold-app-actor" to
        "surefold-app on surefold-actor: a scope is a node, and an actor is a node keyed by its protocol.",
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
val benchmarkModules = setOf("surefold-core-benchmarks")

// Built and tested, never published: surefold-structured calls StructuredTaskScope, a preview API until
// JDK 28 (JEP 543), and a release must not promise an API the JDK has not. surefold-guides holds only tests, of the
// guides (spec 0302).
val unpublished = setOf("surefold-structured", "surefold-guides") + benchmarkModules

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
    group = "dev.surefold"
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
            functions.add("dev.surefold.junit.expect")
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
        // ThreadsOnTimeout, from the shared source below, and no other library's autodetected extension (spec 0308).
        systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
        systemProperty("junit.jupiter.extensions.autodetection.include", "dev.surefold.*")
    }

    // Every module's Jupiter tests get ThreadsOnTimeout: every thread's stack, virtual ones included, before a timed-out
    // test is interrupted (spec 0308). A source shared by path rather than a module, so nothing is published for it.
    // Only where there are tests: a module with none, a benchmark's, would otherwise fail for discovering none.
    if (file("src/test").isDirectory) {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            sourceSets.named("test") {
                kotlin.srcDir(rootProject.file("config/threads-on-timeout/kotlin"))
                resources.srcDir(rootProject.file("config/threads-on-timeout/resources"))
            }
        }
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
        // jar. An empty one: Dokka's were 16 of core 0.18.0's 19 MB on Central,
        // nearly all of it the same scripts and fonts in every module, and
        // Central now caps a publisher's monthly release size. javadoc.io is
        // blank as a result; `dokkaGeneratePublicationHtml` still renders the
        // KDoc locally, and the sources jar carries it to an IDE.
        // A Gradle plugin says which platform it is in its own build script, where
        // `java-gradle-plugin` has been applied: this block runs before that.
        if (name !in gradlePluginModules) {
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
                    sourcesJar = true,
                ),
            )
        }

        pom {
            name.set(this@subprojects.name)
            description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Surefold.")
            url.set("https://github.com/surefold/surefold-core")
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
                url.set("https://github.com/surefold/surefold-core")
                connection.set("scm:git:https://github.com/surefold/surefold-core.git")
                developerConnection.set("scm:git:ssh://git@github.com/surefold/surefold-core.git")
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
            // Snapshots (spec 0281): snapshot.yml runs `publishAllPublicationsToGithubPackagesRepository` with the
            // workflow's token as `githubPackagesUsername`/`githubPackagesPassword`. Releases stay on Central.
            maven {
                name = "githubPackages"
                url = uri("https://maven.pkg.github.com/surefold/surefold-core")
                credentials(PasswordCredentials::class)
            }
        }
        // No relocation POMs at `io.github.matthewjones372` any more (spec 0277 published them at 0.18.0):
        // 0.18.0's send a build on the old group here, and every further version of them would be a
        // second Central deployment per release, which Central now counts against a monthly limit.
    }
}
