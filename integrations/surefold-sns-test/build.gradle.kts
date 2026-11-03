// A topic in the test JVM for a service's own tests (spec 0291): surefold-sns and surefold-sqs-test, whose ElasticMQ
// queues the topic delivers to, and nothing else. NoOtherDependenciesTest asserts that on the classpath a consumer gets.

// What the module may bring, resolved from those two alone: anything else on its classpath was added beside them.
val allowed: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    // The SnsTopic its helpers answer and the SnsClient interface its topic implements.
    api(project(":surefold-sns"))
    // The queues a topic delivers to, and through them ElasticMQ and Jupiter's API.
    api(project(":surefold-sqs-test"))
    allowed(project(":surefold-sns"))
    allowed(project(":surefold-sqs-test"))

    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    inputs.files(allowed).withPropertyName("allowedClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.sns.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.sns.test.allowedClasspath=" + allowed.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
