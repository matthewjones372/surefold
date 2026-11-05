// An actor as a value and a step, on surefold and nothing else.
// NoOtherDependenciesTest asserts exactly that, on the classpath a consumer gets.

plugins {
    // JournalContract: the tests every journal runs, this module's own and any a service writes.
    `java-test-fixtures`
}

dependencies {
    testImplementation(project(":surefold-assert"))
    api(project(":surefold"))

    testFixturesApi("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testFixturesImplementation(project(":surefold-assert"))

    // ActorsGuideTest compiles the examples out of docs/actors.md (spec 0089).
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

// Read at configuration time, so the configuration cache survives it.
val repoRoot = rootProject.projectDir.absolutePath
val guide =
    files(
        rootProject.layout.projectDirectory.file("docs/actors.md"),
        rootProject.layout.projectDirectory.file("docs/cluster.md"),
        rootProject.layout.projectDirectory.file("README.md"),
    )

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    // An edit to the guide is a change to what this task tests, or its examples would ride a cached green.
    inputs.files(guide).withPropertyName("guide")
    // The embedded compiler needs more than Gradle's default heap, or a GC stall trips the 60s timeout.
    maxHeapSize = "2g"
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.actor.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.actor.repoRoot=$repoRoot",
            )
        },
    )
}

// The module's own rules on top of the shared ones: its tests never wait on time. See the file for why.
extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
    config.from(file("detekt.yml"))
}

tasks.named("check") { dependsOn("detektTestFixtures") }
