plugins {
    application
}

dependencies {
    implementation(project(":surefold"))
    implementation(project(":surefold-jdbc-otel"))
    implementation("io.opentelemetry:opentelemetry-sdk:1.67.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation(project(":surefold-junit"))
}

application {
    mainClass.set("io.github.matthewjones372.surefold.jdbc.bench.LoadKt")
    applicationDefaultJvmArgs = listOf(
        "-Xms2g", "-Xmx2g", "-XX:+UseG1GC", "-Dotel.semconv-stability.opt-in=database",
    )
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.jdbc.otel.bench.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
