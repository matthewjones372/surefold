package dev.surefold.junit

import dev.surefold.Limit
import dev.surefold.Resource
import dev.surefold.resource
import org.junit.platform.commons.annotation.Testable

/**
 * Tests written as a Kotlin `object` so the `surefold` engine finds it with no annotation: a [Suite] when they share
 * nothing, a [SharedSuite] when they share one [R]. Each test is registered from `init`, and every check it makes is
 * recorded as its result; a test that throws fails with what it threw. Tests run as forks on virtual threads, at most
 * [limit] at once; with [concurrent] false, each runs alone. The suite's [aspects] wrap each test's.
 * Each test logs and measures into a capture of its own, and a failed test's log lines are reported with it.
 * A [story] told without a title takes the test's name, and its transcript is one of those log lines.
 * `testEach` registers a test for each case, under a container named for the group (spec 0244).
 * It is [Testable] so that IntelliJ, which looks for that on a class's superclasses too, gives each suite a run icon.
 */
@Testable
abstract class AnySuite<R> internal constructor(
    internal val limit: Limit,
    internal val concurrent: Boolean,
    private val aspects: Aspect,
    /** Runs alone in the whole run, when suites run side by side (spec 0253). */
    internal val isolated: Boolean,
) {

    /** What the suite acquires once, hands to each test, and releases after the last. */
    internal abstract val toAcquire: Resource<R>

    private val registered = LinkedHashMap<Key, Registered<R>>()

    private val groups = LinkedHashSet<String>()

    internal val tests: Map<Key, Registered<R>> get() = registered.toMap()

    /** Every group's name in the order registered, those with no cases among them. */
    internal val groupNames: List<String> get() = groups.toList()

    internal fun register(name: String, aspects: Aspect, body: (R) -> Unit) = register(Key(null, name), aspects, body)

    internal fun <T> registerEach(
        group: String,
        cases: Iterable<T>,
        aspects: Aspect,
        named: (T) -> String,
        body: (R, T) -> Unit,
    ) {
        require(groups.add(group)) { "${javaClass.name} already has a group named \"$group\"; a name is its ID" }
        cases.forEach { case -> register(Key(group, named(case)), aspects) { shared -> body(shared, case) } }
    }

    private fun register(key: Key, aspects: Aspect, body: (R) -> Unit) {
        require(key !in registered) {
            if (key.group == null) {
                "${javaClass.name} already has a test named \"${key.name}\"; a name is its ID"
            } else {
                "${javaClass.name}'s group \"${key.group}\" already has a case named \"${key.name}\"; a name is its ID"
            }
        }
        registered[key] = Registered(body, this.aspects + aspects + if (concurrent) Aspect.none else alone)
    }

    internal class Registered<R>(val body: (R) -> Unit, val aspects: Aspect)

    /** A test's ID within its suite: its name, and the group it is a case of, if any. */
    internal data class Key(val group: String?, val name: String) {
        /** How its failures and logs name it: the case's name alone would not say which group it is in. */
        val title: String get() = if (group == null) name else "$group [$name]"
    }
}

/** Tests that share nothing. See [AnySuite]. */
@Suppress("AbstractClassCanBeConcreteClass") // a suite is an object that extends this, never an instance of it
abstract class Suite(
    limit: Limit = Limit.fixed(Runtime.getRuntime().availableProcessors()),
    concurrent: Boolean = true,
    aspects: Aspect = Aspect.none,
    isolated: Boolean = false,
) : AnySuite<Unit>(limit, concurrent, aspects, isolated) {

    final override val toAcquire: Resource<Unit> = resource { }

    /** Registers a test that runs as its [aspects] say, inside the suite's. */
    protected fun test(name: String, aspects: Aspect = Aspect.none, body: () -> Unit) =
        register(name, aspects) { _ -> body() }

    /** Registers a test for each of [cases], named by [named], under a container named [name]; [aspects] are each's. */
    protected fun <T> testEach(
        name: String,
        cases: Iterable<T>,
        aspects: Aspect = Aspect.none,
        named: (T) -> String = { it.toString() },
        body: (T) -> Unit,
    ) = registerEach(name, cases, aspects, named) { _, case -> body(case) }
}

/** Tests that share one [R]. See [AnySuite]. */
abstract class SharedSuite<R>(
    limit: Limit = Limit.fixed(Runtime.getRuntime().availableProcessors()),
    concurrent: Boolean = true,
    aspects: Aspect = Aspect.none,
    isolated: Boolean = false,
) : AnySuite<R>(limit, concurrent, aspects, isolated) {

    /**
     * Acquired once for the suite, handed to each test, and released after the last. If acquiring throws, each
     * test fails with that throw, and what was installed before it is released.
     */
    abstract val shared: Resource<R>

    final override val toAcquire: Resource<R> get() = shared

    /** Registers a test that runs as its [aspects] say, inside the suite's. */
    protected fun test(name: String, aspects: Aspect = Aspect.none, body: (R) -> Unit) =
        register(name, aspects, body)

    /** Registers a test for each of [cases], named by [named], under a container named [name]; [aspects] are each's. */
    protected fun <T> testEach(
        name: String,
        cases: Iterable<T>,
        aspects: Aspect = Aspect.none,
        named: (T) -> String = { it.toString() },
        body: (R, T) -> Unit,
    ) = registerEach(name, cases, aspects, named, body)
}
