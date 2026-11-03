package dev.surefold.junit

import dev.surefold.Deferred
import dev.surefold.Limit
import dev.surefold.LogLine
import dev.surefold.capturingLogs
import dev.surefold.capturingMetrics
import dev.surefold.resourceScope
import dev.surefold.scope
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestTag
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.UniqueIdSelector
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * The configuration parameter, or system property, that gives every test with no timeout of its own one, as
 * `junit.jupiter.execution.timeout.default` does for Jupiter: a Kotlin duration such as `60s` or `10m`.
 */
internal const val DEFAULT_TIMEOUT = "surefold.junit.timeout.default"

/**
 * The configuration parameter, or system property, that says how many suites run at once (spec 0253). One, the
 * default, runs them one after another.
 */
internal const val SUITES_LIMIT = "surefold.junit.suites.limit"

/**
 * Runs every [AnySuite] object the platform's selectors reach, at most [SUITES_LIMIT] suites at once and an isolated
 * one alone, and each suite's tests as forks.
 */
class SurefoldEngine : TestEngine {

    override fun getId(): String = "surefold"

    override fun discover(request: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor =
        EngineDescriptor(uniqueId, "Surefold").also { resolver.resolve(request, it) }

    override fun execute(request: ExecutionRequest) {
        val listener = request.engineExecutionListener
        val engine = request.rootTestDescriptor
        listener.executionStarted(engine)
        val fallback = request.configurationParameters.get(DEFAULT_TIMEOUT).map { value ->
            requireNotNull(Duration.parseOrNull(value.trim())) { "$DEFAULT_TIMEOUT is $value, which is not a duration" }
        }.orElse(null)
        val limit = request.configurationParameters.get(SUITES_LIMIT).map { value ->
            requireNotNull(value.trim().toIntOrNull()?.takeIf { it >= 1 }) {
                "$SUITES_LIMIT is $value, which is not a number of suites of 1 or more"
            }
        }.orElse(1)
        val suites = engine.children.filterIsInstance<SuiteDescriptor>()
        val report = Report(listener)
        // A release that throws leaves the engine unfinished, which the platform reports as the engine's failure.
        GuardedRun.opened { guards ->
            if (limit == 1) {
                suites.forEach { run(it.suite, it, report, fallback, guards) }
            } else {
                sideBySide(suites, Limit.fixed(limit)) { run(it.suite, it, report, fallback, guards) }
            }
        }
        listener.executionFinished(engine, TestExecutionResult.successful())
    }

    /**
     * Runs each of [suites] as a fork within [limit], started in order; an isolated suite waits for those before it,
     * and those after it wait for it.
     */
    private fun sideBySide(suites: List<SuiteDescriptor>, limit: Limit, run: (SuiteDescriptor) -> Unit) = scope {
        val running = mutableListOf<Deferred<Unit>>()
        suites.forEach { suite ->
            if (suite.suite.isolated) running.awaitAll()
            val started = CountDownLatch(1)
            running += async {
                limit.withPermit {
                    started.countDown()
                    run(suite)
                }
            }
            // Waiting for each suite to take its permit starts them in order.
            started.await()
            if (suite.suite.isolated) running.awaitAll()
        }
        running.awaitAll()
    }

    /**
     * Runs [suite]; a test with no timeout of its own, nor its suite's, is held to [fallback] if there is one. Each
     * test holds a permit of every guard the suite leased, taken before it starts, as the suite's limit is.
     */
    private fun <R> run(
        suite: AnySuite<R>,
        descriptor: SuiteDescriptor,
        report: Report,
        fallback: Duration?,
        guards: GuardedRun,
    ) {
        report.started(descriptor)
        val tests = suite.tests
        // Ignored tests are skipped before the resource is acquired, so a failed acquisition does not fail them.
        // A group is started before its cases and finished after the last, so it is open while any of them runs.
        val groups = descriptor.children.filterIsInstance<GroupDescriptor>()
        groups.forEach(report::started)
        val (ignored, cases) = descriptor.descendants.filterIsInstance<TestCaseDescriptor>()
            .partition { tests.getValue(it.key).aspects.ignored != null }
        ignored.forEach { test -> tests.getValue(test.key).aspects.ignored?.let { report.skipped(test, it) } }
        var acquired = false
        val outcome = outcomeOf {
            resourceScope {
                val (shared, leased) = guards.leasesOf { suite.toAcquire.bind() }
                acquired = true
                scope {
                    val running = mutableListOf<Deferred<Unit>>()
                    cases.forEach { test ->
                        val registered = tests.getValue(test.key)
                        val aspects = registered.aspects.timedOrWithin(fallback)
                        if (registered.aspects.alone) running.awaitAll()
                        val started = CountDownLatch(1)
                        running += async {
                            suite.limit.withPermit {
                                holding(leased) {
                                    report.started(test)
                                    started.countDown()
                                    val consoleCopies = ConcurrentHashMap<String, String>()
                                    val (result, lines) = capturingLogs { logs ->
                                        tellingStories(test.key.title, consoleCopies) {
                                            capturingMetrics {
                                                outcomeOf {
                                                    aspects.run({ k, v -> report.entry(test, k, v) }) {
                                                        val (_, checks) = recording { registered.body(shared) }
                                                        checks.verify(test.key.title)
                                                    }
                                                }
                                            }
                                        } to logs.all()
                                    }
                                    report.finished(test, result, lines, consoleCopies)
                                }
                            }
                        }
                        // Waiting for each test to take its permit starts them in registration order.
                        started.await()
                        if (registered.aspects.alone) running.awaitAll()
                    }
                    running.awaitAll()
                }
            }
        }
        close(descriptor, groups, cases.takeUnless { acquired }, outcome, report)
    }

    /**
     * Finishes [suite] and its [groups] after their tests. [unacquired] are the tests the resource was never handed
     * to, failed here with the acquisition's [outcome]; otherwise the tests carry their own results, so a failure left
     * in [outcome] is from around them, such as the release's, and is the suite's.
     */
    private fun close(
        suite: SuiteDescriptor,
        groups: List<GroupDescriptor>,
        unacquired: List<TestCaseDescriptor>?,
        outcome: TestExecutionResult,
        report: Report,
    ) {
        // Each test carries the cause, so it is reported under every test's name and not again for the suite.
        unacquired?.forEach {
            report.started(it)
            report.finished(it, outcome)
        }
        groups.forEach { report.finished(it, TestExecutionResult.successful()) }
        report.finished(suite, if (unacquired == null) outcome else TestExecutionResult.successful())
    }

    /** Runs [block] holding a permit of each of [guards], taken in the order given. */
    private fun holding(guards: List<Guarded<*>>, block: () -> Unit): Unit =
        guards.foldRight(block) { guard, inner -> { guard.withPermit(inner) } }()

    private fun MutableList<Deferred<Unit>>.awaitAll() {
        forEach { it.await() }
        clear()
    }

    /** A test's throw is its result, of whatever type, as Jupiter's is; only the VM's own errors end the run. */
    private fun outcomeOf(block: () -> Unit): TestExecutionResult = try {
        block()
        TestExecutionResult.successful()
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (t: Throwable) {
        TestExecutionResult.failed(t)
    }
}

/** Suites and their forks report from their own threads, and a listener need not be thread-safe. */
private class Report(private val listener: EngineExecutionListener) {
    private val lock = ReentrantLock()

    fun started(test: TestDescriptor) = lock.withLock { listener.executionStarted(test) }

    fun skipped(test: TestDescriptor, why: String) = lock.withLock { listener.executionSkipped(test, why) }

    fun entry(test: TestDescriptor, key: String, value: String) =
        lock.withLock { listener.reportingEntryPublished(test, ReportEntry.from(key, value)) }

    /**
     * A failed test's [lines] are published and printed before its result; a passing test's are dropped. A line in
     * [consoleCopies] is printed as its copy there, so a story is coloured on the console and plain in the report.
     */
    fun finished(
        test: TestDescriptor,
        result: TestExecutionResult,
        lines: List<LogLine> = emptyList(),
        consoleCopies: Map<String, String> = emptyMap(),
    ) = lock.withLock {
        if (result.status == TestExecutionResult.Status.FAILED && lines.isNotEmpty()) {
            val logs = lines.joinToString("\n") { it.rendered() }
            listener.reportingEntryPublished(test, ReportEntry.from("logs", logs))
            val console = lines.joinToString("\n") { it.rendered(consoleCopies[it.message] ?: it.message) }
            println("${(test as? TestCaseDescriptor)?.key?.title ?: test.displayName} logged:\n$console")
        }
        listener.executionFinished(test, result)
    }
}

/** `LEVEL origin message key=value`, and the cause's class and message where there is one. */
private fun LogLine.rendered(message: String = this.message): String =
    (listOfNotNull(level.name.uppercase(), origin, message) + annotations.map { (key, value) -> "$key=$value" })
        .plus(listOfNotNull(cause?.let { "($it)" }))
        .joinToString(" ")

/** A Kotlin `object`: its one instance is the static `INSTANCE` field, read without initialising the class. */
private fun isSuiteObject(type: Class<*>): Boolean = AnySuite::class.java.isAssignableFrom(type) &&
    type.declaredFields.any { it.name == "INSTANCE" && it.type == type && Modifier.isStatic(it.modifiers) }

/** The class [name] names, or null where this classpath has none, as for an ID left from a renamed suite. */
private fun loaded(name: String): Class<*>? = try {
    Class.forName(name, false, Thread.currentThread().contextClassLoader)
} catch (_: ClassNotFoundException) {
    null
}

private object SuiteResolver : SelectorResolver {
    override fun resolve(selector: ClassSelector, context: SelectorResolver.Context): Resolution {
        val type = selector.getJavaClass()
        if (!isSuiteObject(type)) return Resolution.unresolved()
        return context.addToParent { parent -> Optional.of(SuiteDescriptor.of(parent.uniqueId, type)) }
            .map { Resolution.match(Match.exact(it)) }
            .orElse(Resolution.unresolved())
    }

    /**
     * `[engine:surefold]/[suite:…]`, `…/[test:…]`, `…/[group:…]` or `…/[group:…]/[test:…]`, which is how an IDE
     * reruns a suite, one of its tests, a group, or one of a group's cases.
     */
    override fun resolve(selector: UniqueIdSelector, context: SelectorResolver.Context): Resolution {
        val segments = selector.uniqueId.segments
        val type = segments.firstOrNull { it.type == "suite" }?.let { loaded(it.value) }
            ?.takeIf(::isSuiteObject) ?: return Resolution.unresolved()
        val selected = segments.dropWhile { it.type != "suite" }.drop(1)
        return context.addToParent { parent ->
            Optional.of(SuiteDescriptor.of(parent.uniqueId, type).also { it.keepOnly(selected) })
        }.map { Resolution.match(Match.exact(it)) }.orElse(Resolution.unresolved())
    }
}

private val resolver = EngineDiscoveryRequestResolver.builder<EngineDescriptor>()
    .addClassContainerSelectorResolver { isSuiteObject(it) }
    .addSelectorResolver(SuiteResolver)
    .build()

internal class SuiteDescriptor private constructor(id: UniqueId, type: Class<*>, val suite: AnySuite<*>) :
    AbstractTestDescriptor(id, type.simpleName, ClassSource.from(type)) {

    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

    /** Drops every child but the one [path] names, and inside a group every case but the one it names, if it does. */
    fun keepOnly(path: List<UniqueId.Segment>) {
        var within: TestDescriptor = this
        for (segment in path) {
            within.children.filter { it.uniqueId.lastSegment != segment }.forEach(within::removeChild)
            within = within.children.singleOrNull() ?: return
        }
    }

    companion object {
        fun of(parent: UniqueId, type: Class<*>): SuiteDescriptor {
            val suite = type.getField("INSTANCE").get(null) as AnySuite<*>
            val descriptor = SuiteDescriptor(parent.append("suite", type.name), type, suite)
            val groups = LinkedHashMap<String, GroupDescriptor>()
            fun group(name: String, empty: Boolean = false) = groups.getOrPut(name) {
                GroupDescriptor(descriptor.uniqueId.append("group", name), name, type, empty).also(descriptor::addChild)
            }
            suite.tests.forEach { (key, test) ->
                val under = key.group?.let { group(it) } ?: descriptor
                val id = under.uniqueId.append("test", key.name)
                under.addChild(TestCaseDescriptor(id, key, type, test.aspects.tags))
            }
            suite.groupNames.forEach { group(it, empty = true) }
            return descriptor
        }
    }
}

/** A `testEach`'s cases, each a test of its own. */
internal class GroupDescriptor(id: UniqueId, name: String, type: Class<*>, private val registeredEmpty: Boolean) :
    AbstractTestDescriptor(id, name, ClassSource.from(type)) {

    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

    // Registered with no cases, it is still reported, as an empty container; one a filter emptied is pruned.
    override fun mayRegisterTests(): Boolean = registeredEmpty
}

internal class TestCaseDescriptor(id: UniqueId, val key: AnySuite.Key, type: Class<*>, private val tags: Set<TestTag>) :
    AbstractTestDescriptor(id, key.name, ClassSource.from(type)) {

    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST

    override fun getTags(): Set<TestTag> = tags
}
