package dev.surefold.app.gradle

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.withClue
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A build that runs, because what the plugin does is register a task and fail a build. The surefold-app under test is
 * this repository's own, handed over as a file dependency.
 *
 * The generated project declares no toolchain: it has no resolver to provision one with, and the matrix runner holds
 * only the JDK it was set up with.
 */
class SurefoldWiringPluginTest {

    private fun project(dir: File, wiring: String, graph: String) {
        val underTest = System.getProperty("surefold.app.underTest")
        val checker = System.getProperty("surefold.checker.repo")
        val version = System.getProperty("surefold.checker.version")
        withClue("the build must pass -Dsurefold.app.underTest; see surefold-app-gradle/build.gradle.kts") {
            underTest.shouldNotBeNull()
        }
        withClue("the build must pass -Dsurefold.checker.repo; see surefold-app-gradle/build.gradle.kts") {
            checker.shouldNotBeNull()
        }
        val jars = underTest!!.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }

        File(dir, "settings.gradle.kts")
            .writeText(
                """
            pluginManagement {
                repositories {
                    maven { url = uri("$checker") }
                    gradlePluginPortal()
                }
            }

            rootProject.name = "under-test"
            """
                    .trimIndent(),
            )
        File(dir, "build.gradle.kts")
            .writeText(
                """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("dev.surefold.wiring") version "$version"
            }

            // Where the compiler plugin comes from; the Kotlin plugin resolves it by coordinates.
            repositories {
                maven { url = uri("$checker") }
                mavenCentral()
            }

            dependencies { implementation(files($jars)) }

            $wiring
            """
                    .trimIndent(),
            )
        val source = File(dir, "src/main/kotlin/Under.kt")
        source.parentFile.mkdirs()
        source.writeText(graph)
    }

    private fun run(dir: File, vararg args: String) = runner(dir, *args).buildAndFail()

    private fun runner(dir: File, vararg args: String) =
        GradleRunner.create().withProjectDir(dir).withArguments(*args, "--stacktrace")

    @Test
    fun `a graph the compiler could not judge is still caught by the task that runs it`(@TempDir dir: File) {
        // Unreadable on purpose: the compiler gives up on it, so this is the task's catch alone,
        // which is the division of work the whole design rests on.
        project(dir, "", UNREADABLE_AND_FAULTY)

        val result = run(dir, "surefoldWiring")

        result.task(":surefoldWiring")?.outcome shouldBe TaskOutcome.FAILED
        // Also the only place the no-reflect label is exercised: this generated project has
        // kotlin-reflect nowhere, and without stripping its notice the sentence reads
        // "Pump (Kotlin reflection is not available) needs DataSource (Kotlin reflection ...)".
        result.output shouldContain "Pump needs DataSource, and nothing builds it"
        withClue("the IDE makes an entry in the Build window out of the compiler's own shape") {
            // Canonical, because a temporary directory on this platform is reached through a symlink
            // and the checker reports the path it actually opened.
            val source = File(dir, "src/main/kotlin/Under.kt").canonicalPath
            result.output shouldContain "e: file://$source:"
        }
    }

    @Test
    fun `compiling is enough to fail, without waiting for check or for a run`(@TempDir dir: File) {
        project(dir, "", FAULTY)

        val result = run(dir, "classes")

        withClue("the whole ask is that the fault arrives where a compile error would") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        result.output shouldContain "Pump needs DataSource"
    }

    /** Prints what the plugin gave the test task: the colour settings, and whether each is one of its inputs. */
    private val showsTestSettings =
        """
        tasks.register("testSettings") {
            val test = tasks.test.get()
            doLast {
                listOf("FORCE_COLOR", "NO_COLOR").forEach { name ->
                    println("env ${'$'}name=" + test.environment[name] + " input=" + test.inputs.properties[name])
                }
                println("property surefold.test.colour=" + test.systemProperties["surefold.test.colour"])
            }
        }
        """
            .trimIndent()

    /** This JVM's environment, with [extra] on top: TestKit replaces the environment whole. */
    private fun environment(vararg extra: Pair<String, String>): Map<String, String> =
        System.getenv().filterKeys { it != "FORCE_COLOR" && it != "NO_COLOR" } + extra

    @Test
    fun `FORCE_COLOR and NO_COLOR in Gradle's environment reach every test task, as inputs`(@TempDir dir: File) {
        project(dir, showsTestSettings, SOUND)

        val out =
            runner(dir, "testSettings")
                .withEnvironment(environment("FORCE_COLOR" to "1", "NO_COLOR" to "1"))
                .build()
                .output

        out shouldContain "env FORCE_COLOR=1 input=1"
        out shouldContain "env NO_COLOR=1 input=1"
    }

    @Test
    fun `-Psurefold test colour reaches the test worker as a system property`(@TempDir dir: File) {
        project(dir, showsTestSettings, SOUND)

        val out =
            runner(dir, "testSettings", "-Psurefold.test.colour=always").withEnvironment(environment()).build().output

        out shouldContain "property surefold.test.colour=always"
    }

    @Test
    fun `with none of them set, the test task is as it was`(@TempDir dir: File) {
        project(dir, showsTestSettings, SOUND)

        val out = runner(dir, "testSettings").withEnvironment(environment()).build().output

        out shouldContain "env FORCE_COLOR=null input=null"
        out shouldContain "env NO_COLOR=null input=null"
        out shouldContain "property surefold.test.colour=null"
    }

    @Test
    fun `a sound graph passes check and leaves a diagram behind`(@TempDir dir: File) {
        project(dir, "", SOUND)

        val result = runner(dir, "check").build()

        result.task(":surefoldWiring")?.outcome shouldBe TaskOutcome.SUCCESS
        File(dir, "build/reports/surefold/Under.mmd").readText() shouldContain "graph TD"
    }

    @Test
    fun `the checker runs inside the compiler, which is what puts a fault in the editor`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", SOUND)

        val result = runner(dir, "compileKotlin").build()

        withClue("the same checkers the IDE runs in K2 mode, so this is the editor's answer too") {
            result.output shouldContain "surefold-app: Under provides [Pump]"
        }
    }

    @Test
    fun `a missing key is a compiler error on the recipe that asked`(@TempDir dir: File) {
        project(dir, "", FAULTY)

        val result = run(dir, "compileKotlin")

        withClue("the whole ask was an error while you type, and this is the compiler's own") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        withClue("the sentence surefoldWiring prints for the same fault, so a reader meets one wording") {
            result.output shouldContain "Pump needs DataSource, and nothing builds it"
        }
    }

    @Test
    fun `surefold-app-actor's actors and actor are read from the reader's list too`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", LIBRARY_ACTORS)

        val result = runner(dir, "compileKotlin").build()

        withClue("actors needs nothing, and actor takes Actors and then what its behaviour does") {
            result.output shouldContain "provides [ActorRef<Ping>, Actors, Ledger], and is short of []"
        }
    }

    @Test
    fun `a graph the reader could not follow says so, whether or not it was asked`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("otherwise silence means both a sound graph and a graph nobody looked at") {
            result.output shouldContain "this graph was not read here, and is checked by surefoldWiring alone"
        }
    }

    @Test
    fun `a graph with a choice is read, the choice a node providing its key`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", CHOSEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("read rather than left to surefoldWiring") { result.output shouldNotContain "was not read here" }
        result.output shouldContain "provides [Kind, Pump], and is short of []"
    }

    @Test
    fun `a sound graph compiles without a word`(@TempDir dir: File) {
        project(dir, "", SOUND)

        runner(dir, "compileKotlin").build().output shouldNotContain "surefold-app"
    }

    @Test
    fun `a graph the reader gave up on compiles, and is left to the task`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("a red line under working code costs more than a fault found a moment later") {
            result.output shouldNotContain "nothing builds it"
        }
    }

    @Test
    fun `a module written as a chain, a name and a choice is read through all three`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", COMPOSED)

        runner(dir, "compileKotlin").build().output shouldContain
            "provides [DataSource, Pump, Valve], and is short of []"
    }

    @Test
    fun `a shape the reader does not know abandons the application rather than guessing`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", UNREADABLE)

        val result = runner(dir, "compileKotlin").build()

        withClue("a red line under working code costs more than saying nothing") {
            result.output shouldContain "was not read: gave up at"
            result.output shouldNotContain "is short of"
        }
    }

    @Test
    fun `the compiler says nothing about a graph unless it is asked to`(@TempDir dir: File) {
        project(dir, "", SOUND)

        runner(dir, "compileKotlin").build().output shouldNotContain "surefold-app: checking"
    }

    @Test
    fun `a node the root does not reach is named at the top of what it took with it`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", FORGOTTEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("one module left out is one line to change, not one line per node under it") {
            result.output shouldContain "and nothing reaches [Audit]"
        }
    }

    @Test
    fun `a root the graph does not build says nothing about what reaches it`(@TempDir dir: File) {
        project(dir, "surefoldWiring { verbose = true }", ROOTLESS)

        val result = runner(dir, "compileKotlin").build()

        withClue("an unmatched root unreaches every node at once, which is the one wrong answer") {
            result.output shouldNotContain "nothing reaches"
        }
    }

    @Test
    fun `a node nothing reaches is a warning on the recipe that built it`(@TempDir dir: File) {
        project(dir, "", FORGOTTEN)

        val result = runner(dir, "compileKotlin").build()

        withClue("the sentence surefoldWiring prints for the same fault, so a reader meets one wording") {
            result.output shouldContain "surefold-app: nothing reaches Audit, and it is built on every start"
        }
        withClue("a node built for nothing is waste, not a broken graph") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        }
        withClue("one edit put Ledger out of reach too; naming it is that edit reported twice") {
            result.output shouldNotContain "nothing reaches Ledger"
        }
    }

    @Test
    fun `a graph the reader gave up on says nothing about what reaches what`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        withClue("a warning under correct code costs more than one surefoldWiring prints a moment later") {
            runner(dir, "compileKotlin").build().output shouldNotContain "nothing reaches"
        }
    }

    @Test
    fun `a key two recipes provide is a warning on the one that wins`(@TempDir dir: File) {
        project(dir, "", TWICE)

        val result = runner(dir, "compileKotlin").build()

        val shadowed = TWICE.lines().indexOfFirst { it.contains("single<Pump>") } + 1

        withClue("the sentence surefoldWiring prints for the same fault, and the line it names") {
            result.output shouldContain "surefold-app: Pump is provided twice; this one wins over Under.kt:$shadowed"
        }
        withClue("a shadowed recipe is a warning; only the task decides whether one stops a build") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        }
    }

    @Test
    fun `a replacement that says it is one is not an accident to report`(@TempDir dir: File) {
        project(dir, "", OVERRIDDEN)

        withClue("overriding forgives the collisions its own merge introduces, and so does this") {
            runner(dir, "compileKotlin").build().output shouldNotContain "provided twice"
        }
    }

    @Test
    fun `the branches of a choice are alternatives, not two recipes for one key`(@TempDir dir: File) {
        project(dir, "", COMPOSED)

        withClue("every branch provides DataSource and every assembly gets one of them") {
            runner(dir, "compileKotlin").build().output shouldNotContain "provided twice"
        }
    }

    @Test
    fun `two recipes that need each other are an error naming the ring`(@TempDir dir: File) {
        project(dir, "", RING)

        val result = run(dir, "compileKotlin")

        withClue("a cycle is a FAIL at runtime, so it is an error here") {
            result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        }
        withClue("the path surefoldWiring prints for the same ring, in the same order") {
            result.output shouldContain "surefold-app: cycle DataSource -> Pump -> DataSource"
        }
    }

    @Test
    fun `a graph the reader gave up on says nothing about a ring either`(@TempDir dir: File) {
        project(dir, "", UNREADABLE)

        runner(dir, "compileKotlin").build().output shouldNotContain "cycle"
    }

    @Test
    fun `a warning stops the build where the extension asks it to`(@TempDir dir: File) {
        project(dir, "surefoldWiring { failOn = \"WARN\" }", WARNED)

        run(dir, "surefoldWiring").output shouldContain "provided twice"
    }

    private fun source(dir: File, set: String, text: String) {
        val file = File(dir, "src/$set/kotlin/Waits.kt")
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    @Test
    fun `a sleep in a test is named, with eventually beside it`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "test", SLEEPS)

        val result = runner(dir, "compileTestKotlin").build()

        result.output shouldContain "Thread.sleep waits a fixed time"
        result.output shouldContain "TimeUnit.sleep waits a fixed time"
        result.output shouldContain "surefold-test's eventually(within)"
    }

    @Test
    fun `a sleep in main code is not a test's, and is left alone`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", SLEEPS)

        runner(dir, "compileKotlin").build().output shouldNotContain "waits a fixed time"
    }

    @Test
    fun `a scheduler built by hand in main code is named, with Stream tick beside it`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", SCHEDULERS)

        val result = runner(dir, "compileKotlin").build()

        result.output shouldContain "Executors.newScheduledThreadPool schedules work by hand"
        result.output shouldContain "Executors.newSingleThreadScheduledExecutor schedules work by hand"
        result.output shouldContain "ScheduledThreadPoolExecutor(..) schedules work by hand"
        result.output shouldContain "Timer(..) schedules work by hand"
        result.output shouldContain "Stream.tick(every) started on the graph's StreamBackend"
        withClue("an executor that schedules nothing is not a scheduler") {
            result.output shouldNotContain "newVirtualThreadPerTaskExecutor"
        }
    }

    @Test
    fun `a run that parks on its root is named, with awaitShutdown beside it`(@TempDir dir: File) {
        project(dir, "", PARKED)

        val result = runner(dir, "compileKotlin").build()

        result.output shouldContain "run parks on block()"
        result.output shouldContain "park on awaitShutdown()"
    }

    @Test
    fun `a run that awaits shutdown, or returns, is left alone`(@TempDir dir: File) {
        project(dir, "", AWAITED)
        runner(dir, "compileKotlin").build().output shouldNotContain "run parks on"

        project(dir, "", SOUND)
        runner(dir, "compileKotlin").build().output shouldNotContain "run parks on"
    }

    @Test
    fun `a sleep that means it is silenced by name`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "test", "@file:Suppress(\"SUREFOLD_SLEEP_IN_TEST\")\n$SLEEPS")

        runner(dir, "compileTestKotlin").build().output shouldNotContain "waits a fixed time"
    }

    @Test
    fun `I-O, blocking and forks inside a step are errors, each named`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", EFFECTS_IN_A_STEP)

        val result = runner(dir, "compileKotlin").buildAndFail()

        listOf(
                "FileInputStream(..)",
                "Socket(..)",
                "DriverManager.getConnection",
                "Thread.sleep",
                "TimeUnit.sleep",
                "Future.get",
                "Deferred.await",
                "Lock.lock",
                "synchronized",
                "parMap",
                "parZip",
                "raceN",
                "scope",
                "Scope.async",
                "readText",
                "writeText",
                "readln",
            )
            .forEach { call -> result.output shouldContain "$call runs again each time the step does" }
        withClue("kotlin.io.encoding only computes, so Base64 is left alone") {
            result.output shouldNotContain "Base64"
        }
        withClue("a helper taking Transaction, and a firstOf branch, are inside the step too") {
            result.output.split("Thread.sleep runs again").size shouldBe 4
        }
    }

    @Test
    fun `a write to state from outside a step, and peek, are errors inside it`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", CAPTURES)

        val result = runner(dir, "compileKotlin").buildAndFail()

        result.output shouldContain "count is a var from outside the step, written again each time the step runs"
        result.output shouldContain "sold is a var from outside the step, written again each time the step runs"
        result.output shouldContain "total is a var from outside the step, written again each time the step runs"
        result.output shouldContain "peek reads outside the step; read it with get()"
        withClue("a var declared inside the step, or inside a helper taking Transaction, is the step's own") {
            result.output shouldNotContain "mine is a var"
            result.output shouldNotContain "tally is a var"
        }
        withClue("a property of an object the step was handed is left alone") {
            result.output shouldNotContain "seen is a var"
        }
    }

    @Test
    fun `Surefold's own effects, and printing, inside a step are errors that point at afterCommit`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", SUREFOLD_EFFECTS)

        val result = runner(dir, "compileKotlin").buildAndFail()

        listOf(
                "logInfo",
                "Logger.log",
                "Counter.increment",
                "Gauge.set",
                "Histogram.record",
                "Hub.publish",
                "println",
                "print",
            )
            .forEach { call ->
                result.output shouldContain
                    "$call runs again each time the step does; once the step commits, run " + "it in afterCommit { }"
            }
        result.output shouldContain
            "ActorRef.tell runs again each time the step does; once the step commits, " +
                "tell it with tellAfterCommit(ref, message)"
    }

    @Test
    fun `what an afterCommit block does is left alone, since it runs once the step commits`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", AFTER_COMMIT)

        runner(dir, "compileKotlin").build().output shouldNotContain "the step does"

        withClue("a transact inside afterCommit is a step of its own, and is checked again") {
            source(dir, "main", AFTER_COMMIT.replace("Thread.sleep(1)", "transact { Thread.sleep(1) }"))
            runner(dir, "compileKotlin").buildAndFail().output shouldContain "Thread.sleep runs again each time"
        }
    }

    @Test
    fun `the same calls outside a step are left alone`(@TempDir dir: File) {
        project(dir, "", SOUND)
        source(dir, "main", EFFECTS_OUTSIDE_A_STEP)

        runner(dir, "compileKotlin").build().output shouldNotContain "runs again each time the step does"
    }
}

private const val IMPORTS =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single
import kotlin.reflect.typeOf

class Pump
class DataSource
"""

private const val FAULTY =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single { _: DataSource -> Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

private const val SOUND =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A chain, a name it is reached through, and a choice whose branches are unioned. */
private const val COMPOSED =
    """$IMPORTS
class Valve

private val chosen: Module =
    if (System.getenv("X") == null) single<DataSource> { DataSource() } else single<DataSource> { DataSource() }

private val named: Module = single { _: DataSource -> Pump() } + single { _: DataSource -> Valve() }

object Under : SurefoldApp<Pump>() {
    override val module: Module = named + chosen
    override fun AppScope.run(root: Pump) = Unit
}
"""

/**
 * surefold-app-actor's factories, compiled against the real module. The reader holds its own copy of what each takes,
 * and this is the only build that would notice that copy going stale. surefold-integrations does the same for the
 * factories its modules ship.
 */
private const val LIBRARY_ACTORS =
    """
import dev.surefold.actor.ActorRef
import dev.surefold.actor.behaviour
import dev.surefold.actor.stay
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.actor.actor
import dev.surefold.app.actor.actors
import dev.surefold.app.single

class Ping
class Ledger

object Under : SurefoldApp<ActorRef<Ping>>() {
    override val module: Module =
        actors() +
            single<Ledger> { Ledger() } +
            actor<Ping, Ledger>("ping") { _ -> behaviour<Ping, Unit>(Unit) { _, _, _ -> stay() } }
    override fun AppScope.run(root: ActorRef<Ping>) = Unit
}
"""

/** Unreadable and short of a key: what only a task that runs the graph can catch. */
private const val UNREADABLE_AND_FAULTY =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = listOf(single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A module out of a collection: legal, sound, and nothing a reader can follow. */
private const val UNREADABLE =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        listOf(single<DataSource> { DataSource() }, single { _: DataSource -> Pump() }).reduce { a, b -> a + b }
    override fun AppScope.run(root: Pump) = Unit
}
"""

private const val WARNED =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<Pump> { Pump() } + single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A node nothing takes, and a node only that one takes: one edit, and so one thing to say. */
private const val FORGOTTEN =
    """$IMPORTS
class Ledger
class Audit(val ledger: Ledger)

object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() } + single<Ledger> { Ledger() } + single { l: Ledger -> Audit(l) }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** A graph that builds no root: sound to read, and unanswerable about what reaches what. */
private const val ROOTLESS =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module = single<DataSource> { DataSource() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Two recipes for one key, on two lines, so the report has a line to name that is not its own. */
private const val TWICE =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() } +
            single<Pump> { Pump() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** The same collision, said out loud. */
private const val OVERRIDDEN =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.overriding
import dev.surefold.app.single

class Pump

object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Pump> { Pump() }.overriding(single<Pump> { Pump() })
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Each of two recipes needing the other: legal to write, impossible to assemble. */
private const val RING =
    """$IMPORTS
object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single { _: DataSource -> Pump() } + single { _: Pump -> DataSource() }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Both sleeps the JDK has, the way a test reaches for them. */
private const val SLEEPS =
    """
import java.util.concurrent.TimeUnit

fun waits() {
    Thread.sleep(200)
    TimeUnit.MILLISECONDS.sleep(200)
}
"""

/** Every way the JDK builds a scheduler, and one executor that is not one. */
private const val SCHEDULERS =
    """
import java.util.Timer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor

fun schedulers() = listOf(
    Executors.newScheduledThreadPool(1),
    Executors.newSingleThreadScheduledExecutor(),
    ScheduledThreadPoolExecutor(1),
    Timer(),
    Executors.newVirtualThreadPerTaskExecutor(),
)
"""

/** A root whose own wait ends only when it is stopped, which is the release, after run returns. */
private const val PARKED =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single

class Server { fun block() = Unit }

object Under : SurefoldApp<Server>() {
    override val module: Module = single<Server> { Server() }
    override fun AppScope.run(root: Server) { root.block() }
}
"""

/** The same root, with run parked where surefold can end it. */
private const val AWAITED =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.single

class Server { fun block() = Unit }

object Under : SurefoldApp<Server>() {
    override val module: Module = single<Server> { Server() }
    override fun AppScope.run(root: Server) { awaitShutdown() }
}
"""

/** A setting that picks the module providing the root, made inside the graph (spec 0136). */
private const val CHOSEN =
    """
import dev.surefold.app.AppScope
import dev.surefold.app.SurefoldApp
import dev.surefold.app.Module
import dev.surefold.app.choice
import dev.surefold.app.single

class Pump
enum class Kind { Small, Large }

object Under : SurefoldApp<Pump>() {
    override val module: Module =
        single<Kind> { Kind.Small } + choice<Kind, Pump> { _ -> single<Pump> { Pump() } }
    override fun AppScope.run(root: Pump) = Unit
}
"""

/** Every kind of call the step checker refuses, inside transact, a firstOf branch and a Transaction helper. */
private const val EFFECTS_IN_A_STEP =
    """
import dev.surefold.Deferred
import dev.surefold.Shared
import dev.surefold.Transaction
import dev.surefold.parMap
import dev.surefold.parZip
import dev.surefold.raceN
import dev.surefold.scope
import dev.surefold.transact
import java.io.FileInputStream
import java.net.Socket
import java.sql.DriverManager
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.io.File
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.path.writeText

val seats = Shared(10)
val guard = ReentrantLock()

fun Transaction.take() {
    Thread.sleep(1)
    seats.update { it - 1 }
}

fun effects(future: Future<Int>, deferred: Deferred<Int>) = transact {
    FileInputStream("seats.txt")
    Socket("box-office", 80)
    DriverManager.getConnection("jdbc:h2:mem:")
    Thread.sleep(1)
    TimeUnit.MILLISECONDS.sleep(1)
    future.get()
    deferred.await()
    guard.lock()
    synchronized(guard) { seats.get() }
    parMap(listOf(1)) { it }
    parZip({ 1 }, { 2 }) { a, b -> a + b }
    raceN({ 1 }, { 2 })
    scope { async { 1 }.await() }
    firstOf({ Thread.sleep(1) }, { take() })
    File("seats.txt").readText()
    Path.of("seats.txt").writeText("10")
    readln()
    Base64.encode(byteArrayOf(1))
}
"""

/** The same calls, in a function that is not a step. */
private const val EFFECTS_OUTSIDE_A_STEP =
    """
import dev.surefold.Deferred
import dev.surefold.Shared
import dev.surefold.parMap
import dev.surefold.parZip
import dev.surefold.raceN
import dev.surefold.scope
import dev.surefold.transact
import java.io.FileInputStream
import java.net.Socket
import java.sql.DriverManager
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

val seats = Shared(10)
val guard = ReentrantLock()

fun effects(future: Future<Int>, deferred: Deferred<Int>) {
    FileInputStream("seats.txt")
    Socket("box-office", 80)
    DriverManager.getConnection("jdbc:h2:mem:")
    Thread.sleep(1)
    TimeUnit.MILLISECONDS.sleep(1)
    future.get()
    deferred.await()
    guard.lock()
    synchronized(guard) { seats.peek() }
    parMap(listOf(1)) { it }
    parZip({ 1 }, { 2 }) { a, b -> a + b }
    raceN({ 1 }, { 2 })
    scope { async { 1 }.await() }
    println(seats.peek())
    transact { seats.update { it - 1 } }
}
"""

/** Writes to state from outside a step, a peek, and the writes a step owns or cannot judge. */
private const val CAPTURES =
    """
import dev.surefold.Shared
import dev.surefold.Transaction
import dev.surefold.transact

var total = 0

class Order { var seen = 0 }

class BoxOffice {
    private val free = Shared(10)
    private var sold = 0

    fun hold(order: Order): Int {
        var count = 0
        transact {
            count += 1
            sold++
            total = free.peek()
            var mine = free.get()
            mine -= 1
            free.set(mine)
            order.seen = mine
        }
        return count
    }
}

fun Transaction.counted(free: Shared<Int>): Int {
    var tally = 0
    tally += free.get()
    return tally
}
"""

/** Surefold's own effects, and printing, each the kind a step wants to happen once it has committed. */
private const val SUREFOLD_EFFECTS =
    """
import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.logInfo
import dev.surefold.stream.Hub
import dev.surefold.transact

val free = Shared(10)

fun hold(log: Logger, line: LogLine, sold: Counter, left: Gauge, sizes: Histogram, orders: ActorRef<String>, events: Hub<String>) =
    transact {
        free.update { it - 1 }
        logInfo("held one")
        log.log(line)
        sold.increment(1.0)
        left.set(free.get().toDouble())
        sizes.record(1.0)
        orders.tell("held")
        events.publish("held")
        println("held")
        print("held")
    }
"""

/** Every kind of effect, each inside an afterCommit block in a step. */
private const val AFTER_COMMIT =
    """
import dev.surefold.Counter
import dev.surefold.Shared
import dev.surefold.actor.ActorRef
import dev.surefold.logInfo
import dev.surefold.transact

val free = Shared(10)

fun hold(sold: Counter, orders: ActorRef<String>) = transact {
    free.update { it - 1 }
    afterCommit {
        logInfo("held one")
        sold.increment(1.0)
        orders.tell("held")
        Thread.sleep(1)
    }
}
"""
