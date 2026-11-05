package dev.surefold.actor

import dev.surefold.Schedule
import dev.surefold.capturingMetrics
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.scope

/** Fails once when told to. */
private data object MeteredTrip

object ActorMetricsSuite : Suite() {
    init {
        test("a tell to a stopped actor and a restart are each counted once, with the scope's tags") {
            capturingMetrics { captured ->
                scope {
                    tagMetrics("node" to "shop-1")
                    val quiet = spawn("quiet", behaviour<MeteredTrip, Unit>(Unit) { _, _, _ -> stay() })
                    stop(quiet).await()
                    quiet.tell(MeteredTrip)

                    val tripping =
                        spawn(
                            "tripping",
                            behaviour<MeteredTrip, Unit>(Unit) { _, _, _ -> error("tripped") },
                            restart = Schedule.recurs(1),
                        )
                    tripping.tell(MeteredTrip)
                    awaitIdle()
                }

                captured.counter("surefold.actor.dead_letters") shouldBe 1.0
                captured.tags("surefold.actor.dead_letters") shouldBe mapOf("node" to "shop-1", "reason" to "stopped")
                captured.counter("surefold.actor.restarts") shouldBe 1.0
                captured.tags("surefold.actor.restarts") shouldBe mapOf("node" to "shop-1")
            }
        }

        test("a dead letter counted before the scope is tagged is counted with the tags once it is") {
            capturingMetrics { captured ->
                scope {
                    val quiet = spawn("quiet", behaviour<MeteredTrip, Unit>(Unit) { _, _, _ -> stay() })
                    stop(quiet).await()
                    quiet.tell(MeteredTrip)
                    tagMetrics("node" to "shop-1")
                    quiet.tell(MeteredTrip)
                }

                captured.tags("surefold.actor.dead_letters") shouldBe mapOf("node" to "shop-1", "reason" to "stopped")
            }
        }
    }
}
