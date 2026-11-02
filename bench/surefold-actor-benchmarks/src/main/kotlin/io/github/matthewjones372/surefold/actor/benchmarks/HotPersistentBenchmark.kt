package io.github.matthewjones372.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
import dev.surefold.actor.EventCodec
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.journal
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.persistent
import io.github.matthewjones372.surefold.app.liquibase.migrate
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val PAYMENTS = 1_000

private data class Pay(val cents: Int, val done: CountDownLatch)

private val cents = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/**
 * One persistent actor taking [PAYMENTS] payments told at once, on a real Postgres in a container: the
 * single account every payment goes to, which sharding cannot spread. [batch] 1 is one append per payment, as
 * before spec 0086; 64 decides the payments waiting and writes them in one append.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class HotPersistentBenchmark {

    @Param("1", "64")
    var batch: Int = 1

    private lateinit var postgres: PostgresServer
    private lateinit var merchant: ActorRef<Pay>

    @Setup(Level.Trial)
    fun start(surefold: Surefold) {
        postgres = PostgresServer()
        val data = postgres.dataSource
        migrate(data, "surefold/journal/jdbc/postgres.sql")
        surefold.scope.journal(JdbcJournal(data))
        merchant = surefold.actor(
            "merchant-$batch",
            persistent<Pay, Int, Int>(
                id = PersistenceId("merchant", UUID.randomUUID().toString()),
                empty = 0,
                codec = cents,
                command = { _, _, pay -> persist(pay.cents).then { pay.done.countDown() } },
                event = { sum, paid -> sum + paid },
                batch = batch,
            ),
        )
    }

    @TearDown(Level.Trial)
    fun stop() = postgres.close()

    @Benchmark
    fun payments() {
        val done = CountDownLatch(PAYMENTS)
        repeat(PAYMENTS) { merchant.tell(Pay(1, done)) }
        done.await()
    }
}
