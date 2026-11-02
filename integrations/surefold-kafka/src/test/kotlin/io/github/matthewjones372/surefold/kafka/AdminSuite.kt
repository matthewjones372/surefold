package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldThrow
import dev.surefold.test.eventually
import org.apache.kafka.clients.admin.AdminClientConfig
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Spec 0162: the topic calls a service's start-up and a runbook make. */
object AdminSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val SETTLE_SECONDS = 10L
    private const val SETTLE_EVERY_MILLIS = 50L

    private fun admin(kafka: KafkaBroker) =
        Kafka.admin(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap))

    init {
        test("ensure creates a topic with its partitions and config, and finds it the second time") { kafka ->
            val topic = Topic("ensured")
            admin(kafka).use { admin ->
                val retention = mapOf("retention.ms" to "604800000")
                admin.ensure(topic, partitions = 3, config = retention) shouldBe
                    Ensured(created = true, grownFrom = null, differing = emptyMap())
                admin.ensure(topic, partitions = 3, config = retention) shouldBe
                    Ensured(created = false, grownFrom = null, differing = emptyMap())
                admin.describe(topic).shouldBeRight { it.partitions().size shouldBe 3 }
                (topic in admin.topics()) shouldBe true
            }
        }

        test("ensure grows a topic with too few partitions, and reports config it leaves as it was") { kafka ->
            val topic = Topic("grown")
            kafka.create(topic, 2)
            admin(kafka).use { admin ->
                admin.ensure(topic, partitions = 5, config = mapOf("retention.ms" to "1000")) shouldBe
                    Ensured(created = false, grownFrom = 2, differing = mapOf("retention.ms" to "604800000"))
                // Kafka acknowledges the increase before every broker sees the new partitions.
                eventually(SETTLE_SECONDS.seconds) {
                    admin.describe(topic).shouldBeRight { it.partitions().size shouldBe 5 }
                }
            }
        }

        test("ensure refuses to shrink a topic") { kafka ->
            val topic = Topic("unshrunk")
            kafka.create(topic, 4)
            admin(kafka).use { admin ->
                shouldThrow<IllegalArgumentException>({ admin.ensure(topic, partitions = 2) }) { }
                admin.describe(topic).shouldBeRight { it.partitions().size shouldBe 4 }
            }
        }

        test("a topic the cluster does not have is NoSuchTopic, to describe or delete") { kafka ->
            val topic = Topic("never-made")
            admin(kafka).use { admin ->
                admin.describe(topic) shouldBe NoSuchTopic(topic).left()
                admin.delete(topic) shouldBe NoSuchTopic(topic).left()
            }
        }

        test("delete removes a topic") { kafka ->
            val topic = Topic("deleted")
            kafka.create(topic, 1)
            admin(kafka).use { admin ->
                admin.delete(topic) shouldBe Unit.right()
                // The broker's listing follows the controller by a moment.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS)
                while (topic in admin.topics() && System.nanoTime() < deadline) Thread.sleep(SETTLE_EVERY_MILLIS)
                (topic in admin.topics()) shouldBe false
            }
        }
    }
}
