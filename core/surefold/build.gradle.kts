// Structured forks on virtual threads, failing through surefold's own Fail. The JDK
// and the Kotlin standard library and nothing else, which is what
// NoOtherDependenciesTest asserts: a fork answers with an Either, so nothing
// here needs to know what is being served.
dependencies {
    testImplementation(project(":surefold-assert"))
    // ThreadsOnTimeoutTest runs a test that times out from inside a test, to see what its report says (spec 0308).
    testImplementation("org.junit.platform:junit-platform-testkit")

    // Test only: surefoldFuture's cancel has to reach surefold from a coroutine awaiting it with kotlinx's own
    // await, and NoOtherDependenciesTest keeps it off the main runtime classpath.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Test only (spec 0209): the lock-free commit is checked for linearizability across interleavings, not only by
    // totals coming out right.
    testImplementation("org.jetbrains.lincheck:lincheck:3.7")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
