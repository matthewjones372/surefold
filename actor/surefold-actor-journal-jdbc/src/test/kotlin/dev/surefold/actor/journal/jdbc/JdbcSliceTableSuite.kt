package dev.surefold.actor.journal.jdbc

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThanOrEqual
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

/** A fresh database whose every connection is taken slowly, as from a busy pool, and counted in [opened]. */
private class SlowPool {
    val opened = AtomicInteger()

    val source: DataSource = Postgres.fresh().let { postgres ->
        object : DataSource by postgres {
            override fun getConnection(): Connection {
                opened.incrementAndGet()
                Thread.sleep(20)
                return postgres.connection
            }
        }
    }
}

/** Spec 0105: the slice table is read by one caller at a time, however many appends want it. */
object JdbcSliceTableSuite : Suite() {
    init {
        test("a thousand appends asking a stale table at once read it once, and go on with the map they have") {
            val pool = SlowPool()
            val table = JdbcSliceTable(pool.source, listOf("db-0", "db-1"), refreshEvery = 50.milliseconds)
            val first = table.current()
            Thread.sleep(60)
            pool.opened.set(0)

            val go = CountDownLatch(1)
            // Each thread hands back the map it saw, so a thread that saw another map fails the test.
            val seen = Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                val asked = List(1_000) {
                    threads.submit(
                        Callable {
                            go.await()
                            table.current()
                        },
                    )
                }
                go.countDown()
                asked
            }

            seen.forAll { it.get() shouldBe first }
            pool.opened.get() shouldBeLessThanOrEqual 2
        }

        test("refreshes asked while one is reading share its read") {
            val pool = SlowPool()
            val table = JdbcSliceTable(pool.source, listOf("db-0", "db-1"))
            table.current()
            pool.opened.set(0)

            val go = CountDownLatch(1)
            Executors.newVirtualThreadPerTaskExecutor().use { threads ->
                repeat(200) { threads.submit { go.await().also { table.refresh() } } }
                go.countDown()
            }

            pool.opened.get() shouldBeLessThanOrEqual 10
        }
    }
}
