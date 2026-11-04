// surefold-app on surefold-cluster: a cluster as a node, joined as config says. The backend is found by name on the
// classpath, so this module brings neither fabric8 nor the AWS SDK; the application adds the one it joins through.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-app-actor"))
    api(project(":surefold-app-typesafe"))
    api(project(":surefold-cluster"))
    testImplementation(project(":surefold-assert"))
    // freePort(): a port below the ephemeral range, never handed out twice in a JVM (spec 0197).
    testImplementation(testFixtures(project(":surefold-actor-remote")))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.cluster.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
