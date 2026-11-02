// surefold-stream on surefold's own forks: a run is a pull loop on one virtual thread, and nothing is under it
// but surefold-stream. NoOtherDependenciesTest asserts exactly that, on the classpath a consumer gets.

dependencies {
    api(project(":surefold-stream"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
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

// A backend reads the node tree, which is what the opt-in is for.
kotlin {
    compilerOptions {
        optIn.add("dev.surefold.stream.StreamSpi")
    }
}
