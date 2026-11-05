// Surefold's own assertions (spec 0169). It depends on surefold, for Either, and on opentest4j, whose
// AssertionFailedError carries expected and actual for an IDE to compare; JUnit puts it on every test classpath
// already. Nothing else, which NoOtherDependenciesTest asserts.
dependencies {
    api(project(":surefold"))
    api("org.opentest4j:opentest4j:1.3.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.assert.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
