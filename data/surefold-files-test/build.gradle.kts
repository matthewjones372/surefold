// MinIO in Testcontainers, for a service's own tests of what it stores (spec 0224), and where surefold-files' S3Files
// is held to the contract its fake keeps. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-files"))
    api("org.testcontainers:testcontainers:2.0.5")
    testImplementation(testFixtures(project(":surefold-files")))
    // freePort(): a port below the ephemeral range, never handed out twice in a JVM (spec 0197).
    testImplementation(testFixtures(project(":surefold-actor-remote")))
}

tasks.test {
    // FilesGuideSuite holds docs/files.md to this module's GuideExample.kt.
    inputs.file(rootProject.layout.projectDirectory.file("docs/files.md")).withPropertyName("guide")
    systemProperty("surefold.files.test.repoRoot", rootProject.projectDir.absolutePath)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.files.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
