// Kafka as a surefold Stream on any backend: surefold-stream and the Kafka client, and nothing else.
// NoOtherDependenciesTest asserts that on the classpath a consumer actually gets.

val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api(project(":surefold-stream"))
    // Kafka 4.3, which Confluent 8.3's serializers are built on. surefold-kafka-pekko's connector is built against
    // 3.8 and resolves up to this one, which its tests run on; the connector built on 4 is a 2.0 milestone.
    api("org.apache.kafka:kafka-clients:4.3.0")

    // Both backends a consumer loop is run on in the tests, against one broker.
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(project(":surefold-stream-pekko"))
    // A run on a test's clock, for operators whose records need no broker.
    testImplementation(project(":surefold-stream-test"))
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary:$pekkoVersion")
    // A broker in the test JVM, so the suite needs no Docker (spec 0161).
    testImplementation(project(":surefold-kafka-test"))
    // Runs a test that times out from inside a test, to see what its report says.
    testImplementation("org.junit.platform:junit-platform-testkit")
    // The broker's own log, errors only: a fatal fault is logged here before KafkaBroker turns its exit into a throw.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
