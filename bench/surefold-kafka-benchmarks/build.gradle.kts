/**
 * surefold-kafka against the plain Java client, measured by JMH: zio-kafka's comparison benchmarks ported.
 * The JMH wiring is surefold-stream-benchmarks' taken as it is. Nothing here is published.
 */

val jmhVersion = "1.37"
val scalaBinary = "2.13"

dependencies {
    implementation(project(":surefold-kafka"))
    implementation(project(":surefold-kafka-pekko"))
    implementation(project(":surefold-stream-forks"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
    // The broker in the benchmark's own JVM, as zio-kafka's benchmarks run theirs.
    implementation("io.github.embeddedkafka:embedded-kafka_$scalaBinary:4.3.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

val jmhGenerator = configurations.register("jmhGenerator")

dependencies { jmhGenerator("org.openjdk.jmh:jmh-generator-bytecode:$jmhVersion") }

val generatedStubSources = layout.buildDirectory.dir("generated/jmh/java")
val generatedStubResources = layout.buildDirectory.dir("generated/jmh/resources")

val benchmarkClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    .flatMap { it.destinationDirectory }

val benchmarkRuntimeClasspath = the<SourceSetContainer>()["main"].runtimeClasspath

// Named rather than inherited: a task registered by hand does not pick up the Kotlin toolchain, and
// a number measured on whichever JDK ran the daemon is a number nobody can reproduce.
val toolchains = extensions.getByType<JavaToolchainService>()
val benchmarkJdk = JavaLanguageVersion.of(25)
val toolchainLauncher = toolchains.launcherFor { languageVersion.set(benchmarkJdk) }

val generateBenchmarkStubs = tasks.register<JavaExec>("generateBenchmarkStubs") {
    description = "Generates JMH's benchmark stubs from the compiled Kotlin"
    mainClass.set("org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator")
    classpath(jmhGenerator, benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    inputs.dir(benchmarkClasses).withPropertyName("benchmarkClasses")
    outputs.dir(generatedStubSources)
    outputs.dir(generatedStubResources)
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                benchmarkClasses.get().asFile.path,
                generatedStubSources.get().asFile.path,
                generatedStubResources.get().asFile.path,
                "reflection",
            )
        },
    )
    // The generator appends to `BenchmarkList`, so a renamed benchmark would leave its old name behind.
    doFirst {
        delete(generatedStubSources)
        delete(generatedStubResources)
    }
}

val compileBenchmarkStubs = tasks.register<JavaCompile>("compileBenchmarkStubs") {
    description = "Compiles the generated JMH stubs"
    dependsOn(generateBenchmarkStubs)
    source(generatedStubSources)
    classpath = benchmarkRuntimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/jmh/java"))
    javaCompiler.set(toolchains.compilerFor { languageVersion.set(benchmarkJdk) })
    options.encoding = "UTF-8"
}

/** `./gradlew :surefold-stream-benchmarks:jmh`, with `-PbenchmarkArgs="..."` passed through to JMH. */
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the surefold-kafka JMH benchmarks; nothing else depends on it"
    dependsOn(compileBenchmarkStubs)
    mainClass.set("org.openjdk.jmh.Main")
    classpath(
        compileBenchmarkStubs.map { it.destinationDirectory },
        generatedStubResources,
        benchmarkRuntimeClasspath,
    )
    // A forked JMH child inherits `java.home`, so the launcher decides which JDK is measured.
    javaLauncher.set(toolchainLauncher)
    val results = layout.buildDirectory.file("jmh-result.json").get().asFile
    args("-prof", "gc", "-rf", "json", "-rff", results.path)
    args(providers.gradleProperty("benchmarkArgs").getOrElse("").split(" ").filter { it.isNotBlank() })
}

/** One run of each consumer with its polls and handler calls stamped, into `build/timelines.json`. */
tasks.register<JavaExec>("timelines") {
    group = "verification"
    description = "Stamps one run of each consumer, for the README's timelines"
    mainClass.set("dev.surefold.kafka.benchmarks.TimelinesKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    args(layout.buildDirectory.file("timelines.json").get().asFile.path)
}

/** Where a fresh consumer's run spends its time: the breakdown spec 0154 starts from. */
tasks.register<JavaExec>("experiments") {
    group = "verification"
    description = "Times a fresh consumer's join, polls, commits and close"
    mainClass.set("dev.surefold.kafka.benchmarks.ExperimentsKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
}
