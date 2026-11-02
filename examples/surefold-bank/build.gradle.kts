// A bank you can watch (spec 0094): accounts and transfers sharded across three nodes. An application, never
// published. NoOtherDependenciesTest asserts the runtime classpath is exactly what is declared here.

plugins {
    application
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":surefold-cluster"))
    implementation(project(":surefold-actor-journal-jdbc"))
    implementation(project(":surefold-app-liquibase"))
    // The journal in memory by default, or on Postgres with `--jdbc`.
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // A real Postgres, in a container.
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    // The pages in a real headless Chromium: the only proof they work. 1.56 drives Chromium 141, build 1194.
    testImplementation("com.microsoft.playwright:playwright:1.56.0")
    testImplementation(project(":surefold-assert"))
}

application {
    mainClass.set("dev.surefold.bank.MainKt")
}

// Where the environment names its installed browsers, Playwright uses that Chromium and downloads none. Where it
// names none, as on CI, Playwright downloads its own.
val browsers = providers.environmentVariable("PLAYWRIGHT_BROWSERS_PATH")

tasks.test {
    if (browsers.isPresent) {
        environment("PLAYWRIGHT_BROWSERS_PATH", browsers.get())
        environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    }
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dsurefold.bank.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name })
        },
    )
}
