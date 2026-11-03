// SNS publishing from a surefold Stream on any backend (spec 0287): surefold-stream and the AWS SDK's SNS client, on
// the JDK's own HTTP client, so neither Netty nor Apache's client comes with it. NoOtherDependenciesTest asserts it.

val awsVersion = "2.55.6"

dependencies {
    api(project(":surefold-stream"))
    // For publishBatch over messages read from SQS, which deletes each only after its own publish (spec 0287).
    api(project(":surefold-sqs"))
    api("software.amazon.awssdk:sns:$awsVersion") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:$awsVersion")

    testImplementation(project(":surefold-stream-forks"))
    // Topics that deliver to real queues, for the claims a fake client cannot check (spec 0291).
    testImplementation(project(":surefold-sns-test"))
    testImplementation(project(":surefold-test"))
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
    inputs.file(rootProject.layout.projectDirectory.file("docs/sns.md")).withPropertyName("guide")
    systemProperty("surefold.sns.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.sns.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
