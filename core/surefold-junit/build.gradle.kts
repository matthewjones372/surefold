import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

// Surefold's tests on the JUnit Platform (spec 0174): a TestEngine that runs Suite objects, so Gradle, the IDE and
// CI read the results as they read Jupiter's. It depends on surefold, surefold-assert for how a failed expectation is
// reported (spec 0175), surefold-test for the stories a suite tells (spec 0188), and junit-platform-engine, and nothing
// else, which NoOtherDependenciesTest asserts. junit-platform-commons, which the engine brings, is an API: a suite is
// `@Testable` (spec 0251), and IntelliJ looks for that annotation on the test's own classpath.
plugins {
    kotlin("plugin.power-assert")
}

dependencies {
    api(project(":surefold"))
    implementation(project(":surefold-assert"))
    api(project(":surefold-test"))
    implementation("org.junit.platform:junit-platform-engine:6.1.3")
    api("org.junit.platform:junit-platform-commons:6.1.3")

    testImplementation(project(":surefold-junit-testkit"))
    testImplementation("org.junit.platform:junit-platform-testkit")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.platform:junit-platform-reporting")
    // ExpectationsCompileTest compiles suites that must not compile.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

tasks.test {
    // Suites that fail on purpose, run only by the tests that select them, never by this task.
    exclude("**/junit/samples/**")
    // StoryEngineTest checks a failing story's console copy is coloured and its report is not (spec 0296).
    systemProperty("surefold.test.colour", "always")

    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.junit.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// The timeout watchdog on one carrier (spec 0310): there, a virtual watchdog would never run beside a test that
// spins without yielding. Its own JVM, since the scheduler's size is read once, when the JVM starts.
val testOnOneCarrier =
    tasks.register<Test>("testOnOneCarrier") {
        description = "Runs ThreadDumpTest with the virtual-thread scheduler held to one carrier."
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        filter { includeTestsMatching("dev.surefold.junit.ThreadDumpTest") }
        systemProperty("jdk.virtualThreadScheduler.parallelism", "1")
        systemProperty("jdk.virtualThreadScheduler.maxPoolSize", "1")
    }

tasks.named("check") { dependsOn(testOnOneCarrier) }

// This module's suites, as a user's would be: a failed `expect` draws its values. The plugin passes the diagram to
// `expect`'s message overload, so its runtime, which lays the diagram out compactly, is on the test classpath only.
@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions.add("dev.surefold.junit.expect")
    includedSourceSets.add("test")
}

kotlin {
    compilerOptions {
        // `val _ =` compiles only under the checker; Kotlin's own switch for it warns, and warnings fail the build.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}
