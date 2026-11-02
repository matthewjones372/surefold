package io.github.matthewjones372.surefold.stream

import dev.surefold.fail.Either
import dev.surefold.fail.fold
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Keep
import org.apache.pekko.stream.javadsl.RunnableGraph
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.toJavaDuration
import org.apache.pekko.japi.Pair as PekkoPair

/** The key a Pekko value in a tree is tagged with, and the one this compiler reads. */
internal val Pekko = BackendKey("Pekko")

/** The caller's line, skipping this module's frames as well as surefold-stream's. */
internal fun pekkoSite(): String = buildSite(PekkoStreams::class.java)

/** The Pekko source a stream compiles to, compiled once and kept on the stream. */
// Pekko's Source is a Java generic, so Kotlin reads its element as invariant. Every operator only reads
// from it, so what goes in as `A` comes out as `A`.
@Suppress("UNCHECKED_CAST")
internal val <A : Any> Stream<*, A>.source: Source<A, NotUsed>
    get() = compiled.getOrCompile(Pekko) { node.optimised().toPekko() } as Source<A, NotUsed>

/** The Pekko flow a pipe compiles to, compiled once and kept on the pipe. */
@Suppress("UNCHECKED_CAST")
internal val <In, Out : Any> Pipe<*, In, Out>.flow: Flow<In, Out, NotUsed>
    get() = compiled.getOrCompile(Pekko) { node.optimised().toPekkoFlow() } as Flow<In, Out, NotUsed>

/** The Pekko graph a run compiles to: the switch sits right before the sink, so a stop is the sink completing. */
internal val Run<*, *>.graph: RunnableGraph<PekkoPair<UniqueKillSwitch, CompletionStage<Any>>>
    get() = compiled.getOrCompile(Pekko) {
        node.optimised().toPekko().viaMat(KillSwitches.single(), Keep.right()).toMat(end.toPekko(), Keep.both())
    }

/**
 * Pekko's `unfoldResource`, which reads on the blocking-IO dispatcher so a blocked read holds none of the
 * stream's threads. A woken read ends the source, so a stop is a drain: what it read still reaches the sink.
 *
 * Each crossing from that dispatcher to the stream carries a [Batches]: a read, and the reads after it that
 * `ready` says will not block. One element a crossing cost a 50k-record Kafka run 200 ms (spec 0154).
 *
 * A cancel from downstream wakes it too. Pekko reads ahead, so a `take` that is satisfied finds the source
 * already blocked on the next read, and the cancel cannot land, nor the resource close, until that returns.
 */
@OptIn(SourceSeam::class)
private fun Node.Blocking.blockingSource(): Source<Any, NotUsed> =
    Source.fromMaterializer { _, attributes ->
        val hooks = attributes.hooks()
        val current = AtomicReference<Opened?>(null)
        val closing = hooks.closing()
        Source.unfoldResource(
            { Batches(Opened(this).also { opened -> current.set(opened).also { hooks.onStop(opened::wake) } }) },
            { batches -> Optional.ofNullable(batches.next()) },
            { batches -> closedInto(closing) { batches.opened.close() } },
        ).watchTermination { _, terminated -> terminated.whenComplete { _, _ -> current.get()?.wake() } }
            .mapConcat { batch -> batch }
    }.mapMaterializedValue { NotUsed.getInstance() }

/** A read that may block, then up to [MOST_READY] that will not, handed to the stream together. */
internal class Batches(val opened: Opened) {

    /** Set once a read inside a batch ended the resource, so the batch goes out and nothing is read after it. */
    private val ended = AtomicBoolean(false)

    fun next(): List<Any>? {
        if (ended.get()) return null
        val first = opened.next() ?: return null
        val batch = arrayListOf(first)
        while (batch.size < MOST_READY && opened.ready()) {
            val more = opened.next()
            if (more == null) {
                ended.set(true)
                break
            }
            batch += more
        }
        return batch
    }

    private companion object {
        /** A bound on one batch, for a resource that is always ready; a Kafka poll returns 500 by default. */
        const val MOST_READY = 1024
    }
}

/** [close] run, and its outcome told to [closing], which the run's exit waits on. */
@Suppress("TooGenericExceptionCaught") // Whatever the close threw is the run's to report, not this stage's to lose.
private fun closedInto(closing: CompletableFuture<Unit>, close: () -> Unit) {
    try {
        close()
        closing.complete(Unit)
    } catch (thrown: Throwable) {
        closing.completeExceptionally(thrown)
        throw thrown
    }
}

/** A value a node holds for one backend, read by that backend; anyone else's is a bug in the refusal. */
private fun Any.ownedBy(owner: BackendKey): Any =
    if (owner === Pekko) this else error("a value for $owner reached the Pekko compiler")

/**
 * The Pekko stages a node describes: the same ones the operators used to build as they were called.
 *
 * The casts are unchecked because a node is untyped; [Stream] and [Run] hold the types, and what they
 * read back out of a compiled source is what they put in.
 */
@Suppress("UNCHECKED_CAST")
internal fun Node.toPekko(): Source<Any, NotUsed> =
    when (this) {
        is Node.Native -> value.ownedBy(owner) as Source<Any, NotUsed>

        is Node.Elements -> Source.from(elements)

        is Node.Single -> Source.single(element)

        is Node.Tick ->
            Source.tick(after.toJavaDuration(), every.toJavaDuration(), element)
                .mapMaterializedValue { NotUsed.getInstance() }

        is Node.FromStage -> (stage as CompletionStage<Any>).asSource(onNull)

        is Node.Blocking -> blockingSource()

        is Node.Fail -> Source.failed(DeclaredFailure(error))

        Node.Empty -> Source.empty()

        is Node.Merge -> upstream.toPekko().merge(other.toPekko())

        is Node.Interleave -> upstream.toPekko().interleave(other.toPekko(), segmentSize)

        is Node.ZipWith -> zipped()

        is Node.Prepend -> upstream.toPekko().prepend(first.toPekko())

        is Node.Concat -> upstream.toPekko().concat(next.toPekko())

        is Node.RestartOnDefect -> restarting(upstream.toPekko(), step, Restarts(logger, clock))

        Node.Hole -> error("a pipe's input compiled as though it were a source")

        is Node.Unary -> upstream.toPekko().via(stage())
    }

/** A pipe's tree as the Pekko flow it describes, its [Node.Hole] the flow's input. */
internal fun Node.toPekkoFlow(): Flow<Any, Any, NotUsed> =
    when (this) {
        Node.Hole -> Flow.create()

        // Straight off the hole, the stage is the flow: `Pipe.from(flow).toFlow()` is that same flow.
        is Node.Unary -> if (upstream == Node.Hole) stage() else upstream.toPekkoFlow().via(stage())

        is Node.StreamOnly -> error("a stream compiled as though it were a pipe: $this")
    }

/** The one Pekko stage a single-input operator is, the same whether it sits on a source or a pipe. */
@Suppress("UNCHECKED_CAST")
private fun Node.Unary.stage(): Flow<Any, Any, NotUsed> =
    when (this) {
        is Node.Stage -> value.ownedBy(owner) as Flow<Any, Any, NotUsed>

        is Node.Map -> mapStage()

        is Node.MapOrFail -> mapOrFailStage()

        is Node.Filter -> filterStage()

        is Node.FilterNot -> filterNotStage()

        is Node.Take -> Flow.create<Any>().take(n)

        is Node.Drop -> Flow.create<Any>().drop(n)

        is Node.TakeWhile -> takeWhileStage()

        is Node.DropWhile -> dropWhileStage()

        is Node.Grouped -> Flow.create<Any>().grouped(n).map { batch -> batch.toList() }

        is Node.Sliding -> Flow.create<Any>().sliding(n, step).map { window -> window.toList() }

        // An async boundary as well, so that what is upstream runs ahead on its own, as it does on Forks.
        is Node.Buffer -> Flow.create<Any>().buffer(size, OverflowStrategy.backpressure()).async()

        is Node.GroupedWithin ->
            Flow.create<Any>().groupedWithin(n, within.toJavaDuration()).map { batch -> batch.toList() }

        is Node.Scan -> scanStage()

        is Node.StatefulMap -> statefulMapStage()

        is Node.MapConcat -> mapConcatStage()

        is Node.MapAsync -> mapAsyncStage()

        is Node.Conflate -> conflateStage()

        is Node.Either -> eitherStage()

        is Node.Absolve -> absolveStage()

        is Node.CatchAll ->
            Flow.create<Any>().recoverWithRetries(1, onDeclaredFailure { e: Any? -> f(e).optimised().toPekko() })

        is Node.MapError -> mapErrorStage()

        is Node.OrFailIfEmpty -> orFailIfEmptyStage()

        is Node.FlatMap -> flatMapStage()

        is Node.MapPar -> mapParStage()

        is Node.Fused -> fusedStage()

        is Node.Probed -> probeStage()
    } as Flow<Any, Any, NotUsed>

private fun Node.Map.mapStage(): Flow<Any, *, NotUsed> {
    val body = guarded("map", at, f)
    return Flow.create<Any>().map { a -> body(a) }
}

private fun Node.MapOrFail.mapOrFailStage(): Flow<Any, *, NotUsed> {
    val scope = Failing<Any?>()
    val body = guarded("mapOrFail", at) { a: Any -> scope.f(a) }
    return Flow.create<Any>().map { a -> body(a) }
}

private fun Node.Filter.filterStage(): Flow<Any, *, NotUsed> {
    val test = guarded("filter", at, predicate)
    return Flow.create<Any>().filter { a -> test(a) }
}

private fun Node.FilterNot.filterNotStage(): Flow<Any, *, NotUsed> {
    val test = guarded("filterNot", at, predicate)
    return Flow.create<Any>().filterNot { a -> test(a) }
}

private fun Node.TakeWhile.takeWhileStage(): Flow<Any, *, NotUsed> {
    val test = guarded("takeWhile", at, predicate)
    return Flow.create<Any>().takeWhile { a -> test(a) }
}

private fun Node.DropWhile.dropWhileStage(): Flow<Any, *, NotUsed> {
    val test = guarded("dropWhile", at, predicate)
    return Flow.create<Any>().dropWhile { a -> test(a) }
}

private fun Node.Scan.scanStage(): Flow<Any, *, NotUsed> {
    val carry = guarded("scan", at, f)
    return Flow.create<Any>().scan(zero) { carried, a -> carry(carried, a) }
}

private fun Node.StatefulMap.statefulMapStage(): Flow<Any, *, NotUsed> {
    val step = guarded("statefulMap", at, f)
    return Flow.create<Any>().statefulMap(
        { create() },
        { carried, a -> step(carried, a).let { (next, out) -> PekkoPair.create(next, out) } },
        { carried -> Optional.ofNullable(onComplete(carried)) },
    )
}

private fun Node.MapConcat.mapConcatStage(): Flow<Any, *, NotUsed> {
    val body = guarded("mapConcat", at, f)
    return Flow.create<Any>().mapConcat { a -> body(a) }
}

private fun Node.MapAsync.mapAsyncStage(): Flow<Any, *, NotUsed> {
    val stage = guarded("mapAsync", at, f)
    return if (ordered) {
        Flow.create<Any>().mapAsync(parallelism) { a -> stage(a).orDieOnNull(a, at) }
    } else {
        Flow.create<Any>().mapAsyncUnordered(parallelism) { a -> stage(a).orDieOnNull(a, at) }
    }
}

private fun Node.Conflate.conflateStage(): Flow<Any, *, NotUsed> {
    val start = guarded("conflateWithSeed", at, seed)
    val fold = guarded("conflateWithSeed", at, aggregate)
    return Flow.create<Any>().conflateWithSeed({ a -> start(a) }, { s, a -> fold(s, a) })
}

private fun Node.Either.eitherStage(): Flow<Any, *, NotUsed> {
    val rights: Flow<Any, Either<Any?, Any>, NotUsed> = Flow.create<Any>().map { a -> Either.Right(a) }
    return rights.recoverWithRetries(
        1,
        onDeclaredFailure { e: Any? -> Source.single<Either<Any?, Any>>(Either.Left(e)) },
    )
}

@Suppress("UNCHECKED_CAST")
private fun Node.Absolve.absolveStage(): Flow<Any, *, NotUsed> {
    val decided = guarded("absolve", at) { either: Any ->
        (either as Either<Any?, Any>).fold({ left -> throw DeclaredFailure(left) }, { right -> right })
    }
    return Flow.create<Any>().map { either -> decided(either) }
}

private fun Node.MapError.mapErrorStage(): Flow<Any, *, NotUsed> {
    val mapped = guardedError("mapError", at, f)
    return Flow.create<Any>().recoverWithRetries(
        1,
        onDeclaredFailure { e: Any? -> Source.failed<Any>(DeclaredFailure(mapped(e))) },
    )
}

// The alternative is deferred because a plain `Source.failed` fails at materialisation, which would
// fail every stream through here rather than the empty ones.
private fun Node.OrFailIfEmpty.orFailIfEmptyStage(): Flow<Any, *, NotUsed> =
    Flow.create<Any>().orElse(Source.lazySource { Source.failed<Any>(DeclaredFailure(error)) })

private fun Node.ZipWith.zipped(): Source<Any, NotUsed> {
    val combine = guarded("zipWith", at, f)
    return upstream.toPekko().zipWith(other.toPekko()) { a, b -> combine(a, b) }
}

private fun Node.FlatMap.flatMapStage(): Flow<Any, *, NotUsed> {
    val breadth = breadth
    val build = guarded(if (breadth == null) "flatMapConcat" else "flatMapMerge", at, f)
    return if (breadth == null) {
        Flow.create<Any>().flatMapConcat { a -> build(a).source }
    } else {
        Flow.create<Any>().flatMapMerge(breadth) { a -> build(a).source }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun End.toPekko(): Sink<Any, CompletionStage<Any>> =
    when (this) {
        End.Collect -> Sink.seq<Any>() as Sink<Any, CompletionStage<Any>>
        is End.Fold -> Sink.fold(zero) { total, a -> f(total, a) }
        is End.Native -> value.ownedBy(owner) as Sink<Any, CompletionStage<Any>>
    }
