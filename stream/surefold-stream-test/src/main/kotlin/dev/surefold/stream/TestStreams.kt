package dev.surefold.stream

import dev.surefold.TestClock

/**
 * A backend for tests, on time the test owns: `tick`, `groupedWithin` and `restartOnDefect`'s delays wait
 * on [clock], and on nothing else, so an hour of ticks is one `clock.adjust(1.hours)`.
 *
 * It is the [Forks] pull loop, and so runs the operators Forks does, those three besides, and `mapPar` one
 * element at a time in the order they came. It refuses the rest by its own name.
 *
 * One stage runs at a time, in the same order every time. `start` returns once the run is over or waiting
 * on a later time, and each move of [clock] returns once everything due by then has run, in time order: a
 * test reads top to bottom, and nothing is left running behind an assertion.
 *
 * A stage body that blocks on anything but [clock] blocks the test with it.
 */
class TestStreams(val clock: TestClock = TestClock()) : StreamBackend by ForksOnClock(clock, name = "TestStreams")

/**
 * The elements that have reached the end of a run on [TestStreams] so far, whether or not it is over: what
 * a sink that collects has been given.
 */
@Suppress("UNCHECKED_CAST")
fun <A> Running<*, List<A>>.emitted(): List<A> =
    (this as? Emitting ?: throw IllegalArgumentException("only a run started on TestStreams keeps what it emitted"))
        .emitted() as List<A>
