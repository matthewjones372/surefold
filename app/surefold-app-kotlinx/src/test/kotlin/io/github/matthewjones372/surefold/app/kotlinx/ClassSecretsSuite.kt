package io.github.matthewjones372.surefold.app.kotlinx

import com.typesafe.config.ConfigFactory
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.typesafe.MASK
import io.github.matthewjones372.surefold.app.typesafe.Secrets
import io.github.matthewjones372.surefold.app.typesafe.configOf
import io.github.matthewjones372.surefold.app.typesafe.origins
import kotlinx.serialization.Serializable

@Serializable
private data class Store(val dbUrl: Secret, val replica: Replica)

@Serializable
private data class Replica(val readUrl: Secret)

object ClassSecretsSuite : Suite() {
    init {
        test("a Secret property masks itself in toString and in the report") {
            val hocon =
                """
                petshop {
                  dbUrl = "jdbc:postgresql://user:hunter2@db/shop"
                  replica { readUrl = "jdbc:postgresql://user:hunter3@replica/shop" }
                }
                """

            val store = testApp(configOf(hocon) + config<Store>("petshop")) { store: Store -> store }

            store.dbUrl.reveal() shouldBe "jdbc:postgresql://user:hunter2@db/shop"
            store.toString() shouldNotContain "hunter"
            store.toString() shouldContain MASK
            val report =
                ConfigFactory.parseString(hocon).origins(Secrets.default.andSecretsOf<Store>("petshop")).report()
            withClue("neither name says secret, so only the class can tell the report") {
                report shouldNotContain "hunter"
                report shouldContain "petshop.replica.readUrl"
            }
        }
    }
}
