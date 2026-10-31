dependencies {
    api(project(":surefold-otel"))
    testImplementation(project(":surefold-otel-test"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.jdbc.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
