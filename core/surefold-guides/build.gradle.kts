// Tests only (spec 0302): surefold-core's guides, their examples compiled against the core alone. They were compiled in
// modules that moved to surefold-integrations, which had Pekko and the rest on their classpath; here nothing but core
// is, so a guide that needs an integration cannot compile.

dependencies {
    // The Kotlin compiler in the test JVM, so the guides' examples are compiled rather than believed.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    // What the core guide's examples use beyond surefold itself.
    testImplementation(project(":surefold-cache"))
    // The cookbook's recipes: applications as graphs.
    testImplementation(project(":surefold-app"))
    // For the core guide's coroutine examples, which compile against kotlinx's own `await`.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // For the stream guide and the README's quick start: a description, and the three backends that stay in core.
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(project(":surefold-stream-actors"))
    testImplementation(project(":surefold-stream-test"))
    // For the testing guide's suites, which compile against surefold-junit and surefold-test.
    testImplementation(project(":surefold-test"))
    testImplementation(project(":surefold-assert"))
}

// The pages the suites compile, declared so that editing one re-runs them rather than leaving a stale answer behind.
val repoRoot = rootProject.projectDir.absolutePath
val guides = files(
    rootProject.layout.projectDirectory.file("docs/core.md"),
    rootProject.layout.projectDirectory.file("docs/stream.md"),
    rootProject.layout.projectDirectory.file("docs/testing.md"),
    rootProject.layout.projectDirectory.file("docs/cookbook.md"),
    rootProject.layout.projectDirectory.file("README.md"),
)

tasks.test {
    inputs.files(guides).withPropertyName("guides")
    // The embedded compiler collects a heap's worth of garbage per example; the default stalls past the timeout.
    maxHeapSize = "2g"
    systemProperty("surefold.guides.repoRoot", repoRoot)
}
