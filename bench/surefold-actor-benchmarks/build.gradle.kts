/**
 * surefold-actor measured by JMH against the same actors written on Pekko Typed: the numbers spec 0059 quotes.
 *
 * The JMH wiring is surefold-stream-benchmarks' taken as it is. Nothing here is published, and nothing in
 * `build` runs it.
 */

val jmhVersion = "1.37"
val pekkoVersion = "1.2.1"

// Released on its own line, not with Pekko: the newest release, whose Pekko modules are pinned to pekkoVersion below.
val pekkoJdbcVersion = "1.3.0"
val scalaBinary = "2.13"

dependencies {
    implementation(project(":surefold-actor"))
    implementation(project(":surefold-actor-remote"))
    // A hot persistent actor measured on a real Postgres (spec 0085), in a container.
    implementation(project(":surefold-actor-journal-jdbc"))
    implementation(project(":surefold-app-liquibase"))
    implementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    implementation("org.postgresql:postgresql:42.7.7")
    // A pool per database for the journal across databases (spec 0088), as a service would run one.
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.apache.pekko:pekko-actor-typed_$scalaBinary:$pekkoVersion")
    implementation("org.apache.pekko:pekko-remote_$scalaBinary:$pekkoVersion")
    // Three nodes of a cluster on each side (spec 0092): surefold's sharding against Pekko Cluster Sharding.
    implementation(project(":surefold-cluster"))
    implementation("org.apache.pekko:pekko-cluster-sharding-typed_$scalaBinary:$pekkoVersion")
    // Distributed pub-sub: Pekko's Topic finds its instances on other nodes through the cluster's receptionist.
    implementation("org.apache.pekko:pekko-cluster-typed_$scalaBinary:$pekkoVersion")
    // A persistent entity and a durable producer on each side, on one Postgres per side (spec 0092).
    implementation("org.apache.pekko:pekko-persistence-typed_$scalaBinary:$pekkoVersion")
    implementation("org.apache.pekko:pekko-persistence-jdbc_$scalaBinary:$pekkoJdbcVersion")
    // pekko-persistence-jdbc is built against an older Pekko; every Pekko module must be the one version.
    implementation("org.apache.pekko:pekko-persistence-query_$scalaBinary:$pekkoVersion")
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
}

val jmhGenerator = configurations.register("jmhGenerator")

dependencies { jmhGenerator("org.openjdk.jmh:jmh-generator-bytecode:$jmhVersion") }

val generatedStubSources = layout.buildDirectory.dir("generated/jmh/java")
val generatedStubResources = layout.buildDirectory.dir("generated/jmh/resources")

val benchmarkClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    .flatMap { it.destinationDirectory }

val benchmarkRuntimeClasspath = the<SourceSetContainer>()["main"].runtimeClasspath

// Named rather than inherited, so the JDK measured is the one the README says.
val toolchains = extensions.getByType<JavaToolchainService>()
val benchmarkJdk = JavaLanguageVersion.of(25)
val toolchainLauncher = toolchains.launcherFor { languageVersion.set(benchmarkJdk) }

// `-PcompactHeaders` measures with JDK 25's compact object headers (JEP 519, spec 0276); off, the numbers stay
// comparable with the baselines already recorded.
val compactHeaders = providers.gradleProperty("compactHeaders").isPresent

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

/** `./gradlew :surefold-actor-benchmarks:jmh`, with `-PbenchmarkArgs="..."` passed through to JMH. */
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the surefold-actor JMH benchmarks; nothing else depends on it"
    dependsOn(compileBenchmarkStubs)
    mainClass.set("org.openjdk.jmh.Main")
    classpath(
        compileBenchmarkStubs.map { it.destinationDirectory },
        generatedStubResources,
        benchmarkRuntimeClasspath,
    )
    javaLauncher.set(toolchainLauncher)
    val results = layout.buildDirectory.file("jmh-result.json").get().asFile
    args("-prof", "gc", "-rf", "json", "-rff", results.path)
    args(providers.gradleProperty("benchmarkArgs").getOrElse("").split(" ").filter { it.isNotBlank() })
    // To JMH's forked JVMs, which are the ones measured, not to the launcher.
    if (compactHeaders) args("-jvmArgsAppend", "-XX:+UseCompactObjectHeaders")
}

/** Heap per idle actor, which JMH cannot measure: many actors spawned, each run once, then left idle. */
tasks.register<JavaExec>("footprint") {
    group = "verification"
    description = "Prints the heap each idle actor costs, on surefold and on Pekko"
    mainClass.set("io.github.matthewjones372.surefold.actor.benchmarks.FootprintKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    if (compactHeaders) jvmArgs("-XX:+UseCompactObjectHeaders")
}

/** Process CPU per ask, closed loop and paced, which JMH cannot show (spec 0144). */
tasks.register<JavaExec>("askCpu") {
    group = "verification"
    description =
        "Prints the process CPU each ask costs, closed loop and paced to 50,000 a second, on surefold and on Pekko"
    mainClass.set("io.github.matthewjones372.surefold.actor.benchmarks.AskCpuKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    if (compactHeaders) jvmArgs("-XX:+UseCompactObjectHeaders")
}
