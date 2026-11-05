// A cache with its lookup fixed when it is made (spec 0246): surefold and nothing else. A shared layer, and whatever
// says a value changed, is an adapter's module. NoOtherDependenciesTest asserts the runtime classpath.
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
                "-Dsurefold.cache.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
