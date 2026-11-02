package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A buffer on every backend: upstream runs ahead of a slow reader, and only as far as the buffer lets it. */
object BufferSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-buffer-test")

    private const val SIZE = 4
    private const val SETTLE_SECONDS = 10L

    /**
     * How far past what was read each backend lets upstream get: the buffer, and the one element upstream
     * holds while it waits for room. Pekko's async boundary has an input buffer of its own on top.
     */
    private val backends: List<Pair<Backend, Int>> =
        listOf(
            OnPekko to SIZE + 1 + PEKKO_INPUT_BUFFER, OnForks to SIZE + 1, OnTestStreams to SIZE + 1,
            OnActors to SIZE + 1,
        )

    init {
        backends.forEach { (backend, furthest) ->
            test("a slow reader lets upstream run ahead by the buffer, and no further [${backend.name}]") { system ->
                val pulled = AtomicInteger()
                val aheadWhileReading = AtomicInteger()

                val exit = Stream.from(generateSequence(1) { it + 1 }.asIterable())
                    .map { pulled.incrementAndGet() }
                    .buffer(SIZE)
                    .map { n ->
                        if (n == 1) {
                            Thread.sleep(300)
                            aheadWhileReading.set(pulled.get() - n)
                        }
                        n
                    }
                    .take(1)
                    .runCollect()
                    .run(backend.on(system)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit shouldBe Exit.Done(listOf(1))
                withClue("upstream kept going while the first element was read: ${aheadWhileReading.get()} ahead") {
                    aheadWhileReading.get() shouldBeGreaterThan 0
                    aheadWhileReading.get() shouldBeLessThanOrEqual furthest
                }
            }
        }
    }
}

/** Pekko's default input buffer on an async boundary, `pekko.stream.materializer.max-input-buffer-size`. */
private const val PEKKO_INPUT_BUFFER = 16
