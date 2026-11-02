package io.github.matthewjones372.surefold.actor.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.surefold.actor.ActorRef
import dev.surefold.actor.EventCodec
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.ShardedJournal
import dev.surefold.actor.journal
import dev.surefold.actor.journal.jdbc.GroupCommit
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.persistent
import dev.surefold.app.liquibase.migrate
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

private const val ACCOUNTS = 256
private const val SPREAD_PAYMENTS = 10_000
private const val POOL = 32

private data class Spread(val cents: Int, val done: CountDownLatch)

private val spreadCents = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/**
 * [SPREAD_PAYMENTS] payments over [ACCOUNTS] persistent actors, one event and one append each, on a journal across
 * [databases] Postgres servers, each in a container (spec 0088). The writes are the many-ids kind sharding spreads;
 * the one-account kind is [HotPersistentBenchmark]'s.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class SpreadPersistentBenchmark {

    @Param("1", "2")
    var databases: Int = 1

    /** Each database's appends alone, or sharing commits (spec 0108). */
    @Param("false", "true")
    var groupCommit: Boolean = false

    private lateinit var servers: List<PostgresServer>
    private lateinit var pools: List<HikariDataSource>
    private lateinit var accounts: List<ActorRef<Spread>>

    @Setup(Level.Trial)
    fun start(surefold: Surefold) {
        servers = (1..databases).map { PostgresServer() }
        pools = servers.map { server ->
            migrate(server.dataSource, "surefold/journal/jdbc/postgres.sql")
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = server.url
                    username = server.user
                    password = server.password
                    maximumPoolSize = POOL
                },
            )
        }
        val grouped = if (groupCommit) GroupCommit() else null
        val journals = pools.mapIndexed { i, pool -> "db-$i" to JdbcJournal(pool, groupCommit = grouped) }
        surefold.scope.journal(ShardedJournal(journals))
        val run = UUID.randomUUID()
        accounts = (1..ACCOUNTS).map { n ->
            surefold.actor(
                "account-$databases-$groupCommit-$n",
                persistent<Spread, Int, Int>(
                    id = PersistenceId("account", "$run-$n"),
                    empty = 0,
                    codec = spreadCents,
                    command = { _, _, pay -> persist(pay.cents).then { pay.done.countDown() } },
                    event = { sum, paid -> sum + paid },
                ),
            )
        }
    }

    @TearDown(Level.Trial)
    fun stop() {
        pools.forEach(HikariDataSource::close)
        servers.forEach(PostgresServer::close)
    }

    @Benchmark
    fun payments() {
        val done = CountDownLatch(SPREAD_PAYMENTS)
        repeat(SPREAD_PAYMENTS) { accounts[it % ACCOUNTS].tell(Spread(1, done)) }
        done.await()
    }
}
