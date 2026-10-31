// A tracer that keeps the spans it ends, for a service's own tests (spec 0221). The SDK, and not
// opentelemetry-sdk-testing: that artifact registers a ContextStorageProvider of its own, and with two on the
// classpath OpenTelemetry falls back to its default storage, so surefold-otel's would be off in the very tests
// that check a trace crosses a fork.
val otelVersion = "1.67.0"

dependencies {
    api(project(":surefold-otel"))
    api("io.opentelemetry:opentelemetry-sdk:$otelVersion")
}
