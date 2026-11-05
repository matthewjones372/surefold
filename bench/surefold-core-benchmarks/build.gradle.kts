/**
 * surefold's own hot paths measured by JMH: what a `SurefoldLocal` read, a binding, a log line and a fork cost (spec
 * 0274). The JMH wiring is surefold-actor-benchmarks' taken as it is. Nothing here is published, and nothing in `build`
 * runs it.
 */
val jmhVersion = "1.37"

dependencies {
    implementation(project(":surefold"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
}

val jmhGenerator = configurations.register("jmhGenerator")

dependencies { jmhGenerator("org.openjdk.jmh:jmh-generator-bytecode:$jmhVersion") }

val generatedStubSources = layout.buildDirectory.dir("generated/jmh/java")
val generatedStubResources = layout.buildDirectory.dir("generated/jmh/resources")

val benchmarkClasses =
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin").flatMap { it.destinationDirectory }

val benchmarkRuntimeClasspath = the<SourceSetContainer>()["main"].runtimeClasspath

// Named rather than inherited, so the JDK measured is the one the README says.
val toolchains = extensions.getByType<JavaToolchainService>()
val benchmarkJdk = JavaLanguageVersion.of(25)
val toolchainLauncher = toolchains.launcherFor { languageVersion.set(benchmarkJdk) }

// `-PcompactHeaders` measures with JDK 25's compact object headers (JEP 519, spec 0276); off, the numbers stay
// comparable with the baselines already recorded.
val compactHeaders = providers.gradleProperty("compactHeaders").isPresent

// `-Pjfr` records each measured JVM with JFR's profile settings into build/jfr, one file per process (spec 0279).
// CPU-time sampling (JEP 509) is experimental and Linux-only, so it is added only there.
val jfr = providers.gradleProperty("jfr").isPresent
val jfrDir = layout.buildDirectory.dir("jfr")
val cpuTimeSampling =
    when (System.getProperty("os.name")) {
        "Linux" -> ",jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=10ms"
        else -> ""
    }

/** What every measured JVM is started with on top of its own options: one list, so JMH is handed it once. */
val measuredJvmArgs: List<String> = buildList {
    if (compactHeaders) add("-XX:+UseCompactObjectHeaders")
    if (jfr) {
        val recording = "filename=${jfrDir.get().asFile}/%p.jfr,settings=profile$cpuTimeSampling"
        add("-XX:StartFlightRecording:$recording")
    }
}

val generateBenchmarkStubs =
    tasks.register<JavaExec>("generateBenchmarkStubs") {
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

val compileBenchmarkStubs =
    tasks.register<JavaCompile>("compileBenchmarkStubs") {
        description = "Compiles the generated JMH stubs"
        dependsOn(generateBenchmarkStubs)
        source(generatedStubSources)
        classpath = benchmarkRuntimeClasspath
        destinationDirectory.set(layout.buildDirectory.dir("classes/jmh/java"))
        javaCompiler.set(toolchains.compilerFor { languageVersion.set(benchmarkJdk) })
        options.encoding = "UTF-8"
    }

/** `./gradlew :surefold-core-benchmarks:jmh`, with `-PbenchmarkArgs="..."` passed through to JMH. */
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the surefold JMH benchmarks; nothing else depends on it"
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
    if (measuredJvmArgs.isNotEmpty()) args("-jvmArgsAppend", measuredJvmArgs.joinToString(" "))
    if (jfr) doFirst { jfrDir.get().asFile.mkdirs() }
}
