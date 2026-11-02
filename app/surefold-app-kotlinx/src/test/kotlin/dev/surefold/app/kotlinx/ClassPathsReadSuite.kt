package dev.surefold.app.kotlinx

import com.typesafe.config.ConfigFactory
import dev.surefold.app.typesafe.PathRead
import dev.surefold.app.typesafe.reading
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

@Serializable
private data class Shop(val host: String, val mode: Trading, val database: Database)

@Serializable
private data class Database(val url: Secret, val poolSize: Int)

@Serializable
private enum class Trading { Live, Demo }

@Serializable
private data class Token(val token: Secret?)

/** A class read lists the paths it read as the block form does, its `Secret` marked (spec 0155). */
object ClassPathsReadSuite : Suite() {
    init {
        test("a class with a Secret and a nested section lists exactly its paths, the secret marked") {
            val hocon = ConfigFactory.parseString(
                """
                petshop {
                  host = "0.0.0.0", mode = Live, unread = 1
                  database { url = "jdbc:postgresql://user:pass@db/shop", poolSize = 4 }
                }
                """.trimIndent(),
            )

            val paths = hocon.reading {
                section("petshop") { decoded(serializer<Shop>(), Naming.AsDeclared) }
                paths
            }.getOrNull()

            withClue("the url's name does not say secret; only its type does") {
                paths.orEmpty() shouldContainExactly listOf(
                    PathRead("petshop.host", secret = false),
                    PathRead("petshop.mode", secret = false),
                    PathRead("petshop.database.url", secret = true),
                    PathRead("petshop.database.poolSize", secret = false),
                )
            }
        }

        test("an absent Secret that may be null, with no default, reads as null") {
            val read = ConfigFactory.parseString("petshop {}").reading {
                section("petshop") { decoded(serializer<Token>(), Naming.AsDeclared) }
            }

            read.getOrNull() shouldBe Token(token = null)
        }
    }
}
