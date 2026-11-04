// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "surefold"

// Each module lives in its family's folder but keeps a flat project path, so `:surefold-stream:test`,
// `project(":surefold-stream")` and the published coordinates do not depend on where the folder is.
fun module(group: String, name: String) {
    include(name)
    project(":$name").projectDir = file("$group/$name")
}

module("core", "surefold")
module("core", "surefold-test")
module("core", "surefold-compiler")
module("core", "surefold-replay")
module("core", "surefold-assert")
module("core", "surefold-junit")
module("core", "surefold-junit-testkit")
module("core", "surefold-structured")
module("core", "surefold-cache")
module("core", "surefold-guides")

module("stream", "surefold-stream")
module("stream", "surefold-stream-forks")
module("stream", "surefold-stream-test")
module("stream", "surefold-stream-actors")

module("actor", "surefold-actor")
module("actor", "surefold-actor-remote")
module("actor", "surefold-actor-journal-jdbc")
module("actor", "surefold-actor-projection")

module("cluster", "surefold-cluster")
module("cluster", "surefold-cluster-saga")

module("app", "surefold-app")
module("app", "surefold-app-gradle")
module("app", "surefold-app-compiler")
module("app", "surefold-app-actor")

module("bench", "surefold-core-benchmarks")
