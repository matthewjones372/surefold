// surefold-tenancy-crypto's Kms on AWS KMS (spec 0235): one customer managed key per owner, found by alias. The SDK
// runs on the JDK's own HTTP client, as surefold-files does, so neither Netty nor Apache's client comes with it.
// NoOtherDependenciesTest asserts the runtime classpath.
val awsVersion = "2.55.6"

dependencies {
    api(project(":surefold-tenancy-crypto"))
    api("software.amazon.awssdk:kms:$awsVersion") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:$awsVersion")

    // LocalStack's KMS, in a container.
    testImplementation("org.testcontainers:testcontainers-localstack:2.0.5")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.tenancy.crypto.aws.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// Opt-in real AWS proof; ordinary builds compile the harness but never invoke it.
tasks.register<JavaExec>("kmsRegionalProof") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.surefold.tenancy.crypto.aws.KmsRegionalProof")
    args(providers.gradleProperty("kmsProofArgs").getOrElse("").split(' ').filter { it.isNotBlank() })
}
