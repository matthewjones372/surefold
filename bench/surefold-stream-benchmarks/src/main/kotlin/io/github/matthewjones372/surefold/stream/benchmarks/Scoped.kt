package io.github.matthewjones372.surefold.stream.benchmarks

import dev.surefold.Scope
import dev.surefold.scope
import dev.surefold.stream.Actors
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.openjdk.jmh.annotations.Scope as JmhScope

/**
 * The `Actors` backend on a scope held open for the whole trial, on a thread of its own, since an invocation cannot
 * sit inside one: a service's scope, started once and run on many times, as `Pekko` is its actor system.
 */
@State(JmhScope.Benchmark)
open class Scoped {

    lateinit var actors: Actors

    private val release = CountDownLatch(1)
    private lateinit var holder: Thread

    @Setup(Level.Trial)
    fun start() {
        val opened = AtomicReference<Scope<Nothing>>()
        val ready = CountDownLatch(1)
        holder = Thread.ofPlatform().start {
            scope {
                opened.set(this)
                ready.countDown()
                release.await()
            }
        }
        ready.await()
        actors = Actors(opened.get())
    }

    @TearDown(Level.Trial)
    fun stop() {
        release.countDown()
        holder.join()
    }
}
