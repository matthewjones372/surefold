// For tests of a surefold service (spec 0115). It depends on surefold and nothing else: a failed step is an
// AssertionError, so any assertion library works inside one without this module choosing it.
// NoOtherDependenciesTest asserts that, on the classpath a consumer gets.

dependencies {
    api(project(":surefold"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.test.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
