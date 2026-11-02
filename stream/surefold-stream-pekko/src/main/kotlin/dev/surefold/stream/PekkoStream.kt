package dev.surefold.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage

/**
 * The way in from Pekko, whatever the source materialises.
 *
 * The materialised value is dropped rather than declared, because a `Stream` has none to give: a
 * caller who needs the `Cancellable` a ticker hands back keeps the `Source` and passes a view of it
 * here. One signature rather than two: a second over `Source<A, NotUsed>` would erase to this one.
 */
fun <A : Any> Stream.Companion.from(source: Source<A, *>): Stream<Nothing, A> =
    Stream(Node.Native(source.mapMaterializedValue { NotUsed.getInstance() }, Pekko, "Stream.from", pekkoSite()))

/** The way out to Pekko, open only once nothing is left that a sink would not understand. */
fun <A : Any> Stream<Nothing, A>.toSource(): Source<A, NotUsed> = source

/**
 * Pekko's own builder, on a stage that cannot complete with `null` — the one call to it this library
 * makes, and the reason the ban on it in `config/detekt/detekt.yml` is a ban with an exception.
 */
@Suppress("ForbiddenMethodCall")
internal fun <A : Any> CompletionStage<A>.asSource(onNull: () -> Throwable): Source<A, NotUsed> =
    Source.completionStage(checked(onNull))
