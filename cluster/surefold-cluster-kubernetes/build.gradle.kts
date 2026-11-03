// surefold-cluster on Kubernetes: seeds from the pods API, and a Lease to break an even split.
// The fabric8 client on the JDK's own HTTP client, so no Netty or Vert.x comes with it.
// NoOtherDependenciesTest asserts the runtime classpath.

val fabric8Version = "7.9.0"

dependencies {
    api(project(":surefold-cluster"))
    api("io.fabric8:kubernetes-client-api:$fabric8Version")
    runtimeOnly("io.fabric8:kubernetes-client:$fabric8Version") {
        exclude(group = "io.fabric8", module = "kubernetes-httpclient-vertx")
    }
    runtimeOnly("io.fabric8:kubernetes-httpclient-jdk:$fabric8Version")

    // An API server in the test JVM that keeps what it is sent, so a test lists and updates as a cluster would.
    testImplementation("io.fabric8:kubernetes-server-mock:$fabric8Version")
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
                "-Dsurefold.cluster.kubernetes.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
