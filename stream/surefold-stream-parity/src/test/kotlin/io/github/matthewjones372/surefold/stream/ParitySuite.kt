package io.github.matthewjones372.surefold.stream

import dev.surefold.Limit
import dev.surefold.Resource
import dev.surefold.Schedule
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every operator, described once and run on every backend. A backend answers each case as the case says,
 * or refuses it before it starts, naming the operator it cannot run: a case is never skipped.
 */
object ParitySuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-parity-test")

    private const val SETTLE_SECONDS = 10L

    /** Every backend there is. A new one joins here, and is held to every case below. */
    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnTestStreams, OnActors)

    internal data class Odd(val value: Int)

    /** What a run ends with, as a case states it: `Died` by what its cause says, since no two throwables are equal. */
    internal sealed interface Expected {
        data class Done(val value: Any) : Expected

        data class Failed(val error: Any?) : Expected

        data class Died(val saying: String) : Expected
    }

    /** [run] is built fresh for each backend, so a case that counts starts from nothing on each. */
    internal class Case(
        val name: String,
        val expected: Expected,
        val normalised: (Any) -> Any = { it },
        val run: () -> Run<*, Any>,
    )

    private fun done(value: Any) = Expected.Done(value)

    private fun <A : Any> Stream<*, A>.all(): Run<*, List<A>> = runCollect()

    private val keyedDelays = List(60) { n -> (n * 7 % 4).toLong() }

    /** Every element once, and each key's elements in the order they came. */
    private fun byKey(results: List<Int>): Any = results.sorted() to results.groupBy { it % 3 }

    private val sorted: (Any) -> Any = { (it as List<*>).map { n -> n as Int }.sorted() }

    internal fun cases(): List<Case> = listOf(
        Case("Stream.of", done(listOf(1, 2, 3))) { Stream.of(1, 2, 3).all() },
        Case("Stream.single", done(listOf(7))) { Stream.single(7).all() },
        Case("Stream.blocking", done(listOf(1, 2, 3))) {
            Stream.blocking(
                open = { ArrayDeque(listOf(1, 2, 3)) },
                next = { it.removeFirstOrNull() },
                wake = { },
                close = { it.clear() },
            ).all()
        },
        Case("Stream.blocking, cut short by take", done(listOf(1, 2))) {
            Stream.blocking(
                open = { generateSequence(1) { it + 1 }.iterator() },
                next = { it.next() },
                wake = { },
                close = { },
            ).take(2)
                .all()
        },
        Case("Stream.empty", done(emptyList<Int>())) { Stream.empty().all() },
        Case("Stream.fail", Expected.Failed(Odd(1))) { Stream.fail(Odd(1)).all() },
        Case("Stream.fromStage", done(listOf("ready"))) {
            Stream.fromStage(CompletableFuture.completedFuture("ready")).all()
        },
        Case("Stream.fromStage completing with null", Expected.Failed(Odd(0))) {
            @Suppress("UNCHECKED_CAST")
            val nulled = CompletableFuture.completedFuture(null) as CompletionStage<String>
            Stream.fromStage(nulled, ifNull = Odd(0)).all()
        },
        Case("Stream.tick", done(listOf("t", "t", "t"))) { Stream.tick(5.milliseconds, "t").take(3).all() },
        Case("map", done(listOf(2, 4, 6))) { Stream.of(1, 2, 3).map { it * 2 }.all() },
        Case("mapOrFail", Expected.Failed(Odd(3))) {
            Stream.of(2, 3, 4).mapOrFail { if (it % 2 == 1) fail(Odd(it)) else it }.all()
        },
        Case("filter", done(listOf(2, 4))) { Stream.of(1, 2, 3, 4).filter { it % 2 == 0 }.all() },
        Case("filterNot", done(listOf(1, 3))) { Stream.of(1, 2, 3, 4).filterNot { it % 2 == 0 }.all() },
        Case("take", done(listOf(1, 2))) { Stream.from(generateSequence(1) { it + 1 }.asIterable()).take(2).all() },
        Case("drop", done(listOf(3, 4))) { Stream.of(1, 2, 3, 4).drop(2).all() },
        Case("takeWhile", done(listOf(1, 2))) { Stream.of(1, 2, 3, 1).takeWhile { it < 3 }.all() },
        Case("dropWhile", done(listOf(3, 1))) { Stream.of(1, 2, 3, 1).dropWhile { it < 3 }.all() },
        Case("grouped", done(listOf(listOf(1, 2), listOf(3)))) { Stream.of(1, 2, 3).grouped(2).all() },
        Case("sliding", done(listOf(listOf(1, 2), listOf(2, 3), listOf(3, 4)))) {
            Stream.of(1, 2, 3, 4).sliding(2).all()
        },
        Case("buffer", done((1..20).toList())) { Stream.from(1..20).buffer(4).all() },
        Case("buffer, then take on a stream that never ends", done(listOf(1, 2, 3))) {
            Stream.from(generateSequence(1) { it + 1 }.asIterable()).buffer(4).take(3).all()
        },
        Case("buffer carrying a failure in order", Expected.Failed(Odd(3))) {
            Stream.of(2, 3, 4).mapOrFail { if (it % 2 == 1) fail(Odd(it)) else it }.buffer(2).all()
        },
        Case("groupedWithin", done(listOf(listOf(1, 2), listOf(3)))) {
            Stream.of(1, 2, 3).groupedWithin(2, 1.seconds).all()
        },
        Case("scan", done(listOf(0, 1, 3, 6))) { Stream.of(1, 2, 3).scan(0) { total, n -> total + n }.all() },
        Case("statefulMap", done(listOf("1@0", "2@1", "end@2"))) {
            Stream.of(1, 2).statefulMap({ 0 }, { seen, n -> (seen + 1) to "$n@$seen" }, { seen -> "end@$seen" }).all()
        },
        Case("mapConcat", done(listOf(1, 2, 2))) { Stream.of(1, 2).mapConcat { n -> List(n) { n } }.all() },
        Case("mapAsync", done(listOf(2, 4, 6))) {
            Stream.of(1, 2, 3).mapAsync(2) { CompletableFuture.completedFuture(it * 2) }.all()
        },
        Case("conflateWithSeed", done(listOf(1, 2, 3)), { (it as List<*>).flatMap { batch -> batch as List<*> } }) {
            Stream.of(1, 2, 3).conflateWithSeed({ listOf(it) }, { batch, n -> batch + n }).all()
        },
        Case("mapPar", done(listOf(2, 4, 6))) { Stream.of(1, 2, 3).mapPar(2) { it * 2 }.all() },
        Case("mapParUnordered answers every element once", done((1..60).map { it * 2 }), sorted) {
            Stream.from(1..60).mapParUnordered(8) { n -> (n * 2).also { Thread.sleep(keyedDelays[n - 1]) } }.all()
        },
        Case("mapPar under a Limit", done(listOf(2, 4, 6))) {
            Stream.of(1, 2, 3).mapPar(Limit.fixed(2)) { it * 2 }.all()
        },
        Case("mapParUnordered under an adaptive Limit", done((1..60).map { it * 2 }), sorted) {
            Stream.from(1..60).mapParUnordered(Limit.adaptive("parity", initial = 4, max = 8)) { it * 2 }.all()
        },
        Case("mapAsyncUnordered answers every element once", done((1..60).map { it * 2 }), sorted) {
            Stream.from(1..60).mapAsyncUnordered(8) { n -> CompletableFuture.completedFuture(n * 2) }.all()
        },
        Case(
            "mapParKeyed keeps each key's order",
            done(byKey((0 until 60).toList())),
            { byKey((it as List<*>).map { n -> n as Int }) },
        ) {
            Stream.from(0 until 60)
                .mapParKeyed(8, key = { it % 3 }) { n -> n.also { Thread.sleep(keyedDelays[n]) } }
                .all()
        },
        Case("mapParOrFail", Expected.Failed(Odd(3))) {
            Stream.of(2, 3).mapParOrFail(2) { if (it % 2 == 1) fail(Odd(it)) else it }.all()
        },
        Case("either", done(listOf(1.right(), Odd(3).left()))) {
            Stream.of(1, 3).mapOrFail { if (it == 3) fail(Odd(it)) else it }.either().all()
        },
        Case("absolve", Expected.Failed(Odd(2))) { Stream.of(1.right(), Odd(2).left(), 3.right()).absolve().all() },
        Case("catchAll", done(listOf(1, 9))) {
            Stream.of(1, 3).mapOrFail { if (it == 3) fail(Odd(it)) else it }.catchAll { Stream.of(9) }.all()
        },
        Case("mapError", Expected.Failed(3)) {
            Stream.of(1, 3).mapOrFail { if (it == 3) fail(Odd(it)) else it }.mapError { it.value }.all()
        },
        Case("orElse", done(listOf(1, 8))) {
            Stream.of(1, 3).mapOrFail { if (it == 3) fail(Odd(it)) else it }.orElse(Stream.of(8)).all()
        },
        Case("orFailIfEmpty on an empty stream", Expected.Failed(Odd(0))) {
            Stream.empty().orFailIfEmpty(Odd(0)).all()
        },
        Case("orFailIfEmpty on a stream that emitted", done(listOf(1))) { Stream.of(1).orFailIfEmpty(Odd(0)).all() },
        Case("a defect in map", Expected.Died("map died on 2, built at ParitySuite.kt:")) {
            Stream.of(1, 2).map { n -> check(n < 2) { "too big" }; n }.all()
        },
        Case("concat", done(listOf(1, 2, 3))) { Stream.of(1, 2).concat(Stream.of(3)).all() },
        Case("prepend", done(listOf(1, 2, 3))) { Stream.of(3).prepend(Stream.of(1, 2)).all() },
        Case("zip", done(listOf(1 to "a", 2 to "b"))) { Stream.of(1, 2, 3).zip(Stream.of("a", "b")).all() },
        Case("merge", done(listOf(1, 2, 3, 4)), sorted) { Stream.of(1, 3).merge(Stream.of(2, 4)).all() },
        Case("interleave", done(listOf(1, 2, 3, 4))) { Stream.of(1, 3).interleave(Stream.of(2, 4), 1).all() },
        Case("flatMapConcat", done(listOf(1, 2, 2))) {
            Stream.of(1, 2).flatMapConcat { n -> Stream.from(List(n) { n }) }.all()
        },
        Case("flatMapMerge", done(listOf(1, 2, 2)), sorted) {
            Stream.of(1, 2).flatMapMerge(2) { n -> Stream.from(List(n) { n }) }.all()
        },
        Case("restartOnDefect", done(listOf(1, 2))) {
            val attempts = AtomicInteger()
            Stream.of(1, 2)
                .map { n -> check(attempts.getAndIncrement() > 0) { "first attempt" }; n }
                .restartOnDefect(Schedule.recurs(2))
                .all()
        },
        Case("a pipe spliced in", done(listOf(3, 5))) {
            Stream.of(1, 2, 3, 4).via(Pipe.filter<Int> { it % 2 == 0 }.map { it + 1 }).all()
        },
        Case("runFold", done(10)) { Stream.of(1, 2, 3, 4).runFold(0) { total, n -> total + n } },
    )

    init {
        backends.forEach { backend ->
            cases().forEach { case ->
                test(
                    "every case answers alike on every backend, or is refused naming what it cannot run " +
                        "[${backend.name}] [${case.name}]",
                ) { system -> check(case, backend.on(system)) }
            }
        }
        backends.forEach { backend ->
            test(
                "no backend refuses any case, since every operator in them has no backend of its own [${backend.name}]",
            ) { system ->
                val on = backend.on(system)
                val refused = cases().mapNotNull { case ->
                    case.run().node.firstUnrunnableOn(on)?.let { "${case.name}: ${it.operator}" }
                }
                refused shouldBe emptyList()
            }
        }
    }

    internal fun check(case: Case, backend: StreamBackend) {
        val run = case.run()
        val running = run.start(backend)
        // A run on a test's clock waits for the test to move it: an hour is past every case's time.
        (backend as? TestStreams)?.clock?.adjust(1.hours)
        val exit = running.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
        val unrunnable = run.node.firstUnrunnableOn(backend)
        if (unrunnable != null) {
            return exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("${backend.key} cannot run ${unrunnable.operator}, so it refuses the run naming it") {
                    died.cause.message shouldContain "${unrunnable.operator}"
                    died.cause.message shouldContain "is not something ${backend.key} runs"
                }
            }
        }
        return when (val expected = case.expected) {
            is Expected.Done -> exit.shouldBeInstanceOf<Exit.Done<Any>> { done ->
                case.normalised(done.value) shouldBe expected.value
            }

            is Expected.Failed -> exit shouldBe Exit.Failed(expected.error)

            is Expected.Died -> exit.shouldBeInstanceOf<Exit.Died> { died ->
                (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() } shouldContain
                    expected.saying
            }
        }
    }

    private fun Node.firstUnrunnableOn(backend: StreamBackend): Node? =
        takeIf { !backend.runs(it) } ?: children().firstNotNullOfOrNull { it.firstUnrunnableOn(backend) }
}
