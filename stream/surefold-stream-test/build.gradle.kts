// A backend for tests: every stage on the calling thread, and time a test owns (spec 0048). It is the
// Forks pull loop run in place, so it depends on surefold-stream-forks and on nothing else.
// NoOtherDependenciesTest asserts that, on the classpath a consumer gets.

dependencies {
    api(project(":surefold-stream-forks"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.stream.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// A backend answers to the SPI's key and runs, which is what the opt-in is for.
kotlin {
    compilerOptions {
        optIn.add("dev.surefold.stream.StreamSpi")
    }
}
