val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api(project(":surefold-app"))
    // `ask`, and with it pekko-actor-typed, lives in surefold-actor-pekko (spec 0058).
    api(project(":surefold-actor-pekko"))

    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary:$pekkoVersion")
    // The Kotlin compiler in the test JVM, so the cookbook's recipes are compiled
    // rather than believed.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    // The cookbook's configuration recipe reads a section from its @Serializable class.
    testImplementation(project(":surefold-app-kotlinx"))
    // For the core guide's coroutine examples, which compile against kotlinx's own `await`.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // For the testing guide's suites, which compile against surefold-junit.
    testImplementation(project(":surefold-junit"))
    // For the migration page's examples, which convert to and from Arrow.
    testImplementation(project(":surefold-arrow"))
    testImplementation(project(":surefold-assert"))
    // For the flags guide, which declares and reads flags over the OpenFeature SDK.
    testImplementation(project(":surefold-openfeature"))
    // For the core guide's cache examples, and CacheCallerCompatibilityTest's 0.16 caller.
    testImplementation(project(":surefold-cache"))
}

// The serialization plugin, handed to the embedded compiler as a reader's build applies it, so a
// @Serializable recipe is checked rather than passed through as a bare annotation.
val serializationPlugin: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    serializationPlugin("org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable:2.4.10")
}

val repoRoot = rootProject.projectDir.absolutePath

// The pages CookbookTest and CoreGuideTest compile, declared so that editing one re-runs the tests
// rather than leaving a stale answer behind.
val documentedRecipes = files(
    rootProject.layout.projectDirectory.file("docs/cookbook.md"),
    rootProject.layout.projectDirectory.file("docs/observability.md"),
    rootProject.layout.projectDirectory.file("docs/arrow.md"),
    rootProject.layout.projectDirectory.file("docs/flags.md"),
)

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    inputs.files(documentedRecipes).withPropertyName("documentedRecipes")
    inputs.files(serializationPlugin).withPropertyName("serializationPlugin")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.app.pekko.repoRoot=$repoRoot",
                "-Dsurefold.app.pekko.serializationPlugin=" + serializationPlugin.singleFile.absolutePath,
            )
        },
    )
}
