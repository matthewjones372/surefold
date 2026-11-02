package dev.surefold.app.typesafe

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

/** Every setting, its value, and the line that said so. */
object OriginsSuite : Suite() {
    private fun parsed(hocon: String) = ConfigFactory.parseString(hocon).resolve()

    init {
        test("a setting names its value and where it was written") {
            val origins = parsed("petshop { port = 9090 }").origins()

            val port = origins.single { it.path == "petshop.port" }
            port.value shouldBe "9090"
            withClue("a string parsed rather than read from a file has no filename, and says so") {
                port.where.shouldNotBeNull()
            }
        }

        test("a file names the file and the line") {
            val origins = ConfigFactory.parseResources("origins.conf").resolve().origins()

            origins.single { it.path == "petshop.port" }.where shouldContain "origins.conf:2"
        }

        test("paths come out sorted, so two runs read the same") {
            val origins = parsed("b = 2, a = 1, c = 3").origins()

            origins.map { it.path } shouldContainExactly listOf("a", "b", "c")
        }

        test("a name that says it holds a secret does not print its value") {
            val origins = parsed("""petshop { db { password = "hunter2", pool = 4 } }""").origins()

            val password = origins.single { it.path == "petshop.db.password" }
            password.value shouldBe MASK
            password.redacted shouldBe true
            withClue("the origin is still worth having: it says which file to go and edit") {
                password.where.shouldNotBeNull()
            }
        }

        test("a declared path does not print its value either") {
            val hocon = """petshop { db { url = "jdbc:postgresql://user:pass@host/shop" } }"""

            val origins = parsed(hocon).origins(Secrets.default.and("petshop.db.url"))

            origins.single { it.path == "petshop.db.url" }.value shouldBe MASK
        }

        test("the report names every setting and leaks no secret") {
            val hocon = """petshop { port = 9090, db { password = "hunter2" } }"""

            val said = parsed(hocon).origins().report()

            said shouldContain "surefold-app configuration"
            said shouldContain "petshop.port"
            said shouldContain "9090"
            said shouldContain "petshop.db.password"
            said shouldNotContain "hunter2"
        }

        test("a document with nothing in it reports nothing") {
            parsed("{}").origins().report() shouldBe ""
        }
    }
}
