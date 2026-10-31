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
module("core", "surefold-arrow")
module("core", "surefold-assert")
module("core", "surefold-junit")
module("core", "surefold-junit-testkit")
module("core", "surefold-structured")
module("core", "surefold-cache")

module("stream", "surefold-stream")
module("stream", "surefold-stream-pekko")
module("stream", "surefold-stream-forks")
module("stream", "surefold-stream-test")
module("stream", "surefold-stream-actors")
module("stream", "surefold-stream-parity")

module("actor", "surefold-actor")
module("actor", "surefold-actor-remote")
module("actor", "surefold-actor-remote-protobuf")
module("actor", "surefold-actor-remote-avro")
module("actor", "surefold-actor-remote-kotlinx")
module("actor", "surefold-actor-journal-jdbc")
module("actor", "surefold-actor-projection")

module("cluster", "surefold-cluster")
module("cluster", "surefold-cluster-kubernetes")
module("cluster", "surefold-cluster-aws")

module("app", "surefold-app")
module("app", "surefold-app-pekko")
module("app", "surefold-app-openfeature")
module("app", "surefold-app-openfeature-flagd")
module("app", "surefold-app-liquibase")
module("app", "surefold-app-flyway")
module("app", "surefold-app-typesafe")
module("app", "surefold-app-kotlinx")
module("app", "surefold-app-gradle")
module("app", "surefold-app-compiler")
module("app", "surefold-app-actor")
module("app", "surefold-app-cluster")

module("integrations", "surefold-pekko")
module("integrations", "surefold-actor-pekko")
module("integrations", "surefold-otel")
module("integrations", "surefold-otel-test")
module("integrations", "surefold-jdbc-otel")
module("integrations", "surefold-openfeature")
module("integrations", "surefold-slf4j")
module("integrations", "surefold-micrometer")
module("integrations", "surefold-kafka")
module("integrations", "surefold-kafka-pekko")
module("integrations", "surefold-kafka-test")

module("data", "surefold-jobs")
module("data", "surefold-app-jobs")
module("data", "surefold-jobs-otel")
module("data", "surefold-files")
module("data", "surefold-files-test")
module("data", "surefold-tenancy")
module("data", "surefold-tenancy-test")
module("data", "surefold-jobs-tenancy")
module("data", "surefold-idempotency")
module("data", "surefold-ratelimit")
module("data", "surefold-postgres-notify")
module("data", "surefold-cache-postgres")
module("data", "surefold-cache-tenancy")
module("data", "surefold-cache-redis")
module("data", "surefold-openfeature-postgres")
module("data", "surefold-tenancy-crypto")
module("data", "surefold-tenancy-crypto-aws")
module("data", "surefold-auth")
module("data", "surefold-auth-oidc")

module("bench", "surefold-stream-benchmarks")
module("bench", "surefold-actor-benchmarks")
module("bench", "surefold-kafka-benchmarks")
module("bench", "surefold-jobs-load")
module("bench", "surefold-jdbc-otel-bench")

module("examples", "surefold-bank")
