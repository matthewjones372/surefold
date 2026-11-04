// Durable sagas (spec 0299): plain step code journalled and replayed, on surefold-actor's journal.
// NoOtherDependenciesTest asserts the runtime classpath is surefold-actor and what it brings, and nothing else.

dependencies {
    api(project(":surefold-actor"))
    testImplementation(project(":surefold-junit"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.cluster.saga.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
