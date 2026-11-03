// An SQS queue in the test JVM for a service's own tests (spec 0288): surefold-sqs, ElasticMQ and JUnit's Jupiter API,
// and nothing else. NoOtherDependenciesTest asserts that on the classpath a consumer actually gets.

val elasticMq = "org.elasticmq:elasticmq-rest-sqs_2.13:1.7.1"
val jupiterApi = "org.junit.jupiter:junit-jupiter-api:6.1.3"

// What the module may bring, resolved from those three alone: anything else on its classpath was added beside them.
val allowed: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    // The Queue its helpers answer and the client they hand out, and through surefold-stream the Resource.
    api(project(":surefold-sqs"))
    // The queue itself, on Pekko HTTP; Pekko 1.5 resolves over surefold-pekko's 1.2 where both are (spec 0288).
    implementation(elasticMq)
    // What a Jupiter test class registers it with.
    api(jupiterApi)
    allowed(project(":surefold-sqs"))
    allowed(elasticMq)
    allowed(jupiterApi)

    testImplementation(project(":surefold-stream-forks"))
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
                "-Dsurefold.sqs.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.sqs.test.allowedClasspath=" + allowed.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
