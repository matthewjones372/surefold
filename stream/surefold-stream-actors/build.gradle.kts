// surefold-stream on surefold-actor (spec 0066): a run is an actor that pulls Forks' loop a batch at a time.
// NoOtherDependenciesTest asserts the runtime classpath is surefold-stream-forks and surefold-actor, and nothing else.

dependencies {
    testImplementation(project(":surefold-assert"))
    api(project(":surefold-stream-forks"))
    api(project(":surefold-actor"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.stream.actors.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// A backend reads the node tree and drives Forks' loop, which is what the opt-in is for.
kotlin {
    compilerOptions {
        optIn.add("dev.surefold.stream.StreamSpi")
    }
}
