// The API alone. A service brings its own SDK, exporter and agent; this module
// decides where a Context lives, not what is done with one.
val otelVersion = "1.67.0"

dependencies {
    api(project(":surefold"))
    api("io.opentelemetry:opentelemetry-api:$otelVersion")

    // Spans to read, from surefold-otel-test's collector, and not opentelemetry-sdk-testing's: that artifact
    // registers a ContextStorageProvider of its own, and two on the classpath leave this module's storage untested.
    testImplementation(project(":surefold-otel-test"))
    // Actors to send to, for the trace carried across a message (spec 0122).
    testImplementation(project(":surefold-actor"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
