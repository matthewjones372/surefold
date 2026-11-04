// Durable sagas (spec 0299): plain step code journalled and replayed, run as sharded entities of the cluster.
// NoOtherDependenciesTest asserts the runtime classpath is surefold-cluster and what it brings, and nothing else.

dependencies {
    api(project(":surefold-cluster"))
    testImplementation(project(":surefold-junit"))
    testImplementation(project(":surefold-assert"))
    // freePort() for each node of the three-node tests (spec 0197).
    testImplementation(testFixtures(project(":surefold-actor-remote")))
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
