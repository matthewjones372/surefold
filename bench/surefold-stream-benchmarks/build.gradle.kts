import java.util.Properties

/**
 * surefold-stream measured by JMH: the baseline 0046 and 0047 report against.
 *
 * The JMH wiring is pelican's `benchmarks` module taken as it is: the bytecode generator reads the
 * compiled Kotlin, because `kapt` would be a compiler plugin for one module. Nothing here is
 * published, and nothing in `build` runs it.
 */

val jmhVersion = "1.37"

dependencies {
    implementation(project(":surefold-stream-pekko"))
    implementation(project(":surefold-stream-forks"))
    implementation(project(":surefold-stream-actors"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
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

// `-PcompactHeaders` measures with JDK 25's compact object headers (JEP 519, spec 0276); off, the numbers stay
// comparable with the baselines already recorded.
val compactHeaders = providers.gradleProperty("compactHeaders").isPresent

// `-Pjfr` records each measured JVM with JFR's profile settings into build/jfr, one file per process (spec 0279).
// CPU-time sampling (JEP 509) is experimental and Linux-only, so it is added only there.
val jfr = providers.gradleProperty("jfr").isPresent
val jfrDir = layout.buildDirectory.dir("jfr")
val cpuTimeSampling = when (System.getProperty("os.name")) {
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
    description = "Runs the surefold-stream JMH benchmarks; nothing else depends on it"
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
    // To JMH's forked JVMs, which are the ones measured, not to the launcher.
    if (measuredJvmArgs.isNotEmpty()) args("-jvmArgsAppend", measuredJvmArgs.joinToString(" "))
    if (jfr) doFirst { jfrDir.get().asFile.mkdirs() }
}

/**
 * `-Pbaseline=a1.json,a2.json -Pcandidate=b1.json,b2.json`: red when a row of the candidate is slower than the
 * baseline's. The files are rounds, paired by position: `gate.sh` measures each round's baseline and candidate one
 * after the other, so whatever the machine did over the job falls on both halves of a pair alike.
 *
 * A row is slower when the median of its rounds is more than [tolerance] slower, and all rounds but one are more than
 * half of [tolerance] slower: a time per operation that rose, or a throughput that fell, as each row's JMH mode says.
 * JMH's error bars are printed and decide nothing, since they measure one run's iterations and not the drift between
 * two runs. Every row is written to `jmh-compare.txt`, so drift is visible before it trips.
 *
 * `gate-tolerance.properties`, beside this file, gives a row known to be noisy a tolerance of its own.
 */
tasks.register("jmhCompare") {
    group = "verification"
    description = "Fails when a benchmark row got slower than the baseline's"
    val baseline = providers.gradleProperty("baseline")
    val candidate = providers.gradleProperty("candidate")
    val tolerance = providers.gradleProperty("tolerance").map(String::toDouble).orElse(defaultTolerance)
    val tolerances = layout.projectDirectory.file("gate-tolerance.properties")
    val report = layout.buildDirectory.file("jmh-compare.txt")
    // The rows found slower, one name a line, so gate.sh can measure them again before it believes them.
    val slowerRows = layout.buildDirectory.file("jmh-slower.txt")
    // Paths are read from the repository root, where gate.sh and a person typing them both stand.
    val root = rootProject.projectDir
    doLast {
        fun rounds(files: String) = files.split(",").filter { it.isNotBlank() }.map { scores(root.resolve(it.trim())) }
        val before = rounds(baseline.get())
        val after = rounds(candidate.get())
        // No rounds would compare no rows, and a gate that measured nothing must not pass.
        if (after.isEmpty() || before.size != after.size) {
            throw GradleException("${before.size} baseline round(s) against ${after.size} candidate round(s)")
        }
        val own = Properties().apply { tolerances.asFile.takeIf { it.exists() }?.reader()?.use(::load) }

        // The longest key that is the row's name, or its class, so `MapParBenchmark.surefold` can differ from its
        // class.
        fun toleranceOf(name: String): Double =
            own.stringPropertyNames().filter { name == it || name.startsWith("$it.") }.maxByOrNull { it.length }
                ?.let { own.getProperty(it).trim().toDouble() } ?: tolerance.get()
        val rows = after.flatMap { it.keys }.distinct().sorted().map { name ->
            compared(name, before.zip(after).map { (b, a) -> b[name] to a[name] }, toleranceOf(name), tolerance.get())
        }
        val table = rows.joinToString("\n") { it.line }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(table + "\n")
        logger.lifecycle(table)
        val slower = rows.filter { it.slower }
        slowerRows.get().asFile.writeText(slower.joinToString("") { it.name + "\n" })
        if (slower.isNotEmpty()) {
            val lines = slower.joinToString("\n") { it.line }
            throw GradleException("${slower.size} benchmark row(s) got slower:\n$lines")
        }
    }
}

val defaultTolerance = 0.10

/** A benchmark row's score and error, and whether a higher score is better, as it is for throughput. */
class Score(val score: Double, val error: Double, val higherIsBetter: Boolean)

/** Each benchmark's [Score], by name, from a JMH JSON result. */
fun scores(result: File): Map<String, Score> {
    @Suppress("UNCHECKED_CAST")
    val rows = groovy.json.JsonSlurper().parse(result) as List<Map<String, Any?>>
    return rows.associate { row ->
        @Suppress("UNCHECKED_CAST")
        val metric = row.getValue("primaryMetric") as Map<String, Any?>
        val score = (metric.getValue("score") as Number).toDouble()
        val error = (metric["scoreError"] as? Number)?.toDouble()?.takeUnless { it.isNaN() } ?: 0.0
        (row.getValue("benchmark") as String).substringAfterLast("benchmarks.") to
            Score(score, error, higherIsBetter = row["mode"] == "thrpt")
    }
}

class Compared(val name: String, val line: String, val slower: Boolean)

fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/** One row over its rounds, each a baseline and a candidate score, either missing where that round lacks the row. */
fun compared(name: String, rounds: List<Pair<Score?, Score?>>, tolerance: Double, default: Double): Compared {
    val paired = rounds.mapNotNull { (before, after) -> if (before != null && after != null) before to after else null }
    if (paired.isEmpty()) {
        val score = median(rounds.mapNotNull { it.second?.score })
        return Compared(name, "%-45s %12s -> %10.1f  new".format(name, "", score), false)
    }
    val was = median(paired.map { it.first.score })
    val score = median(paired.map { it.second.score })
    val changes = paired.map { (before, after) -> after.score / before.score - 1 }
    // A time per operation is slower when it rose; a throughput, when it fell.
    val slowdowns = if (paired.first().second.higherIsBetter) changes.map { -it } else changes
    val agreeing = slowdowns.count { it > tolerance / 2 }
    val slower = median(slowdowns) > tolerance && agreeing >= maxOf(1, paired.size - 1)
    val verdict = when {
        slower -> "SLOWER"
        median(slowdowns) > tolerance -> "ok ($agreeing of ${paired.size})"
        else -> "ok"
    }
    val each = changes.joinToString(" ") { "%+.1f".format(it * 100) }
    val own = if (tolerance == default) "" else "  (tolerance %.0f%%)".format(tolerance * 100)
    return Compared(
        name,
        "%-45s %10.1f -> %10.1f  %+6.1f%%  rounds %s  %s%s"
            .format(name, was, score, median(changes) * 100, each, verdict, own),
        slower,
    )
}

/** Each benchmark's pipeline, measured once and drawn with where its time went, into `build/profiles`. */
tasks.register<JavaExec>("profiles") {
    group = "verification"
    description = "Draws each benchmark's pipeline with its profile, as Mermaid and as text"
    mainClass.set("io.github.matthewjones372.surefold.stream.benchmarks.ProfilesKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    val out = layout.buildDirectory.dir("profiles")
    outputs.dir(out)
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.path) })
}
