package dev.surefold.sns.guide

import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.sns.SnsPublisher
import dev.surefold.sns.SnsTopics
import dev.surefold.sns.message
import dev.surefold.sns.snsTopics

object PlacedSuite : SharedSuite<SnsTopics>() {
    override val shared = snsTopics()

    init {
        test("a placed order reaches billing") { sns ->
            val placed = sns.create("guide-placed")
            val billing = sns.queues.create("guide-billing")
            sns.subscribe(placed, billing)

            SnsPublisher(sns.client).publish(placed.message("o-1"))

            sns.queues.receive(billing).map { it.body() } shouldBe listOf("o-1")
            sns.published(placed).map { it.body } shouldBe listOf("o-1")
        }
    }
}
