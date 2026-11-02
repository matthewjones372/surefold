// A stream that names its failure, described and not yet running: surefold and the Arrow that arrives with
// it, and nothing else. NoOtherDependenciesTest asserts exactly that, on the classpath a consumer gets.
// Running one is a backend's job; surefold-stream-pekko is the first.

dependencies {
    api(project(":surefold"))
    testImplementation(project(":surefold-assert"))
}

// Read at configuration time: a provider that reached for the project at
// execution time would not survive the configuration cache.
val repoRoot = rootProject.projectDir.absolutePath
val judgedSources = files(layout.projectDirectory.dir("src/main/kotlin"))

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    // Which sources FunctionalStyleTest judges is decided here, and declared as an input of the task
    // that runs it, so that a violation cannot ride a cached green.
    inputs.files(judgedSources).withPropertyName("judgedSources")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.stream.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.stream.repoRoot=$repoRoot",
                "-Dsurefold.stream.sources=" +
                    judgedSources.joinToString(File.pathSeparator) { it.absolutePath },
            )
        },
    )
}

// The node tree is this module's own, so it opts in to the annotation it puts on it everywhere.
kotlin {
    compilerOptions {
        optIn.add("dev.surefold.stream.StreamSpi")
    }
}
