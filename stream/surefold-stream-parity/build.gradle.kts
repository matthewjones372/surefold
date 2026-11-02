// One suite of described pipelines, run on every backend: a test-only module, so that no backend has to
// depend on another to be held to the same answers. A backend joins by being added to ParitySuite's list.

dependencies {
    testImplementation(project(":surefold-stream-pekko"))
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(project(":surefold-stream-test"))
    testImplementation(project(":surefold-stream-actors"))
}

// The suite asks each backend which operators it runs, which is the node tree the opt-in covers.
kotlin {
    compilerOptions {
        optIn.add("dev.surefold.stream.StreamSpi")
    }
}

// HardenedSuite runs the whole suite this many times on Forks; -Psoak=N for more.
tasks.test {
    val soak = providers.gradleProperty("soak").orElse("200")
    inputs.property("soak", soak)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dsurefold.stream.soak=${soak.get()}") })
}
