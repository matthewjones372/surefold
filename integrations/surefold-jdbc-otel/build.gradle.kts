dependencies {
    api(project(":surefold-otel"))
    implementation("io.opentelemetry.instrumentation:opentelemetry-jdbc:2.33.1-alpha")
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation(project(":surefold-otel-test"))
}

tasks.withType<Test>().configureEach {
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

tasks.test {
    systemProperty("otel.semconv-stability.opt-in", "database")
    systemProperty("surefold.jdbc.database.conventions", "stable")
}

val conventions = mapOf(
    "Legacy" to "",
    "Dual" to "database/dup",
    "Conflicting" to "unknown",
    "ExceptionLogs" to "database",
)
conventions.forEach { (mode, optIn) ->
    val variant = tasks.register<Test>("test${mode}Conventions") {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        javaLauncher.set(tasks.test.flatMap { it.javaLauncher })
        if (optIn.isNotEmpty()) systemProperty("otel.semconv-stability.opt-in", optIn)
        systemProperty(
            "surefold.jdbc.database.conventions",
            when (mode) {
                "Dual" -> "dual"
                "ExceptionLogs" -> "stable"
                else -> "legacy"
            },
        )
        if (mode == "ExceptionLogs") systemProperty("otel.semconv.exception.signal.preview", "logs")
    }
    tasks.check { dependsOn(variant) }
}
