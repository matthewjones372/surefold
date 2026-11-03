// SQS as a surefold Stream on any backend (spec 0286): surefold-stream and the AWS SDK's SQS client, on the JDK's own
// HTTP client, so neither Netty nor Apache's client comes with it. NoOtherDependenciesTest asserts the classpath.

val awsVersion = "2.55.6"

dependencies {
    api(project(":surefold-stream"))
    api("software.amazon.awssdk:sqs:$awsVersion") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:$awsVersion")

    // Both backends a read is run on in the tests: Forks, and Forks on a test clock.
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(project(":surefold-stream-test"))
}

tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("docs/sqs.md")).withPropertyName("guide")
    systemProperty("surefold.sqs.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.sqs.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
