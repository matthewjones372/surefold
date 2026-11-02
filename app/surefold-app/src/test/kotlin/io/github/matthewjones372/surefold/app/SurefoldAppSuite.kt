package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.typeOf

internal class Counter(val started: AtomicReference<String> = AtomicReference(""))

/** Declared as an object, which is what the checker finds and what a service writes. */
internal object Sampled : SurefoldApp<Counter>() {

    override val module: Module = single<Counter> { Counter() }

    override fun AppScope.run(root: Counter) {
        root.started.set("ran")
    }
}

internal class Released : AutoCloseable {
    val closed = AtomicBoolean()

    override fun close() = closed.set(true)
}

/** A service: no run, so the default waits for shutdown. */
internal object Waits : SurefoldApp<Released>() {

    override val module: Module = closingOf { -> Released() }
}

/** A graph short of a key, so the checker has something to fail on. */
internal object Faulty : SurefoldApp<Counter>() {

    override val module: Module = single { _: File -> Counter() }

    override fun AppScope.run(root: Counter) = Unit
}

/** A graph that provides one key twice, which is a warning rather than a failure. */
internal object Warned : SurefoldApp<Counter>() {

    override val module: Module = single<Counter> { Counter() } + single<Counter> { Counter() }

    override fun AppScope.run(root: Counter) = Unit
}

object SurefoldAppSuite : Suite() {
    init {
        test("an application carries the root it starts from") {
            Sampled.root shouldBe typeOf<Counter>()
        }

        test("runApp starts the graph and hands the root to the declared block") {
            val exit = runApp(Sampled)

            exit shouldBe ExitCode.Ok
        }

        test("an application with no run waits for shutdown, and its graph is released after") {
            val shutdown = Shutdown()
            val built = CompletableFuture<Released>()
            val running = Thread.ofVirtual().start {
                Waits.module.use(Waits.root) { root ->
                    built.complete(root as Released)
                    with(Waits) { appScope(shutdown).run(root) }
                }
            }
            val held = built.join()

            // Parked in run rather than returned: the thread is waiting, and nothing has been released.
            while (running.state != Thread.State.WAITING) Thread.onSpinWait()
            held.closed.get() shouldBe false

            shutdown.request()
            running.join()

            held.closed.get() shouldBe true
        }

        test("a faulty graph leaves with a failure rather than running the block") {
            runApp(Faulty) shouldBe ExitCode.Failed
        }

        test("an application is asked for its findings from the root it declares") {
            val findings = Faulty.module.findings(Faulty.root)

            findings.map { it.severity } shouldContainExactly listOf(Severity.FAIL)
            findings.single().error.shouldBeMissingFile()
        }
    }

    private fun WiringError.shouldBeMissingFile() {
        (this as WiringError.Missing).key shouldBe typeOf<File>()
        toString() shouldContain "File"
    }
}

internal class Ambiguous

internal object TwoOfThem : SurefoldApp<Ambiguous>() {
    override val module: Module = singleOf(::Ambiguous)
    override fun AppScope.run(root: Ambiguous) = Unit
}

/** The root is read off the declaration, so it is written once. */
object RootFromTheDeclarationSuite : Suite() {
    init {
        test("the type between the angle brackets is the root") {
            TwoOfThem.root shouldBe typeOf<Ambiguous>()
        }
    }
}
