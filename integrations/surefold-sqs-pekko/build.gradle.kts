// surefold-sqs through Pekko's own SQS connector (spec 0290): surefold-sqs, surefold-stream-pekko and the connector,
// whose async client runs on its own Pekko HTTP transport, so Netty does not come with it.
// NoOtherDependenciesTest asserts that on the classpath a consumer actually gets.

val scalaBinary = "2.13"

// 1.3.0 is built against pekko-stream 1.1 and AWS SDK 2.42, which resolve up to the repository's Pekko and to
// surefold-sqs's SDK; the suite runs on those (spec 0290). 2.0.0-M1 needs Pekko 2.
val connectorVersion = "1.3.0"

dependencies {
    api(project(":surefold-sqs"))
    api(project(":surefold-stream-pekko"))
    api("org.apache.pekko:pekko-connectors-sqs_$scalaBinary:$connectorVersion") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }

    // ElasticMQ, which brings Pekko 1.5 onto the test classpath (spec 0288), so the suites run there on 1.5 and
    // make their actor system without the typed testkit, whose 1.2 Pekko refuses to mix with it.
    testImplementation(project(":surefold-sqs-test"))
    testImplementation(project(":surefold-test"))
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.sqs.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
