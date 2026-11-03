// surefold-app on surefold-actor: a scope as a node, and an actor as a node keyed by the ref of its protocol.
// NoOtherDependenciesTest asserts the runtime classpath is surefold-app and surefold-actor, and nothing else.

dependencies {
    api(project(":surefold-app"))
    api(project(":surefold-actor"))
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
                "-Dsurefold.app.actor.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

dependencies {
    // A cluster made in the actors' scope, to show it leaves as the application is released (spec 0080).
    testImplementation(project(":surefold-cluster"))
}
