package dev.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.app.StartupError
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.app.use
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.typesafe.config.Config as Hocon

private data class Db(val url: String, val poolSize: Int, val idle: Duration, val replicas: List<String>)

private class Pooling(val db: Db)

private class Checked(val url: String) {
    init {
        require(url.isNotEmpty()) { "a url is required" }
    }
}

object SectionsSuite : Suite() {
    private val file: Hocon = ConfigFactory.parseResources("application.conf").resolve()

    private fun db(): Reading.() -> Db = {
        Db(string("url"), int("poolSize"), duration("idle"), strings("replicas"))
    }

    init {
        test("everything HOCON can say still works through the reading") {
            file.reading { section("database", db()) }.getOrNull().shouldNotBeNull { db ->
                withClue("substitution, a list and a duration, none of them re-encoded as a string") {
                    db.url shouldBe "jdbc:postgresql://db.internal/shopping"
                    db.idle shouldBe 30.seconds
                    db.replicas shouldContainExactly listOf("one", "two")
                }
            }
        }

        test("every fault comes back, in Typesafe Config's own words") {
            file.reading { section("broken", db()) }.leftOrNull().shouldNotBeNull { faults ->
                val said = faults.joinToString { it.why }
                withClue("one message listing what is wrong beats one deploy per fault") {
                    faults.size shouldBe 4
                }
                said shouldContain "url"
                withClue("the exception's own message names the origin, which this could not word better") {
                    said shouldContain "application.conf"
                }
            }
        }

        test("a constructor that validates a discarded value still answers with the faults") {
            file.reading { section("broken") { Checked(string("url")) } }
                .leftOrNull().shouldNotBeNull { faults ->
                    withClue("the missing url is what to fix; the require it tripped on the way past is not") {
                        faults.joinToString { it.why } shouldContain "url"
                    }
                }
        }

        test("a module reads its own section and nothing else names its paths") {
            val app = single<Hocon> { file } + config("database", db()) + single { db: Db -> Pooling(db) }

            testApp(app) { pooling: Pooling -> pooling.db.poolSize } shouldBe 5
        }

        test("a section that cannot be read refuses the start") {
            val app = single<Hocon> { file } + config("broken", db()) + single { db: Db -> Pooling(db) }

            app.use { _: Pooling -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Refused> { it.reason shouldContain "url" }
            }
        }

        test("the when a service writes over its own settings is a Module, and needs nothing from here") {
            val carts: dev.surefold.app.Module =
                when (file.getString("database.url").substringAfter("://").substringBefore("/")) {
                    "db.internal" -> single<Pooling> { Pooling(Db("", 0, Duration.ZERO, emptyList())) }
                    else -> single<Pooling> { Pooling(Db("other", 0, Duration.ZERO, emptyList())) }
                }

            testApp(carts) { pooling: Pooling -> pooling.db.url } shouldBe ""
        }
    }
}
