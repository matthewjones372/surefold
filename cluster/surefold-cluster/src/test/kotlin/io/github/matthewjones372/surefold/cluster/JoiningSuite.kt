package io.github.matthewjones372.surefold.cluster

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.actor.remote.Node
import kotlin.time.Duration.Companion.seconds

/** Spec 0096: seeds as config writes them, and a way to join found by its name. */
object JoiningSuite : Suite() {
    init {
        test("a seed written host and port is whichever node answers there") {
            Node.at("10.0.0.7:25520") shouldBe Node("", "10.0.0.7", 25520)
            Node.at(" bank-1.bank:25520 ") shouldBe Node("", "bank-1.bank", 25520)
            Discovery.static("a:1", "b:2").seeds() shouldContainExactly listOf(Node("", "a", 1), Node("", "b", 2))
        }

        test("a seed without a port, or with one no node listens on, is refused") {
            listOf("10.0.0.7", "10.0.0.7:", ":25520", "host:0", "host:70000", "host:port").forAll { bad ->
                shouldThrow<IllegalArgumentException>({ Node.at(bad) }) { e -> e.message shouldContain bad }
            }
        }

        test("static, dns and srv are on every classpath surefold-cluster is") {
            Joins.available().keys shouldContainAll listOf("static", "dns", "srv")
        }

        test("static joins through its seeds") {
            val options =
                JoinOptions(25520, 20.seconds, mapOf("seeds" to listOf("a:1", "b:2")), "surefold.cluster.static")
            Joins.named("static", options).use { joining ->
                joining.discovery.seeds() shouldContainExactly listOf(Node("", "a", 1), Node("", "b", 2))
            }
        }

        test("a value a backend needs and was not given is refused with the path it is read at") {
            shouldThrow<IllegalArgumentException>({
                Joins.named("static", JoinOptions(25520, 20.seconds, emptyMap(), "surefold.cluster.static"))
            }) { e -> e.message shouldBe "surefold.cluster.static.seeds is missing" }
            shouldThrow<IllegalArgumentException>({
                val options = mapOf("name" to "x", "port" to "high")
                Joins.named("dns", JoinOptions(25520, 20.seconds, options, "surefold.cluster.dns"))
            }) { e -> e.message shouldBe "surefold.cluster.dns.port is not a number" }
        }

        test("a backend whose module is not on the classpath is refused, naming the module") {
            shouldThrow<IllegalArgumentException>({
                Joins.named("kubernetes", JoinOptions(25520, 20.seconds))
            }) { e -> e.message shouldBe "join = kubernetes needs surefold-cluster-kubernetes on the classpath" }
            shouldThrow<IllegalArgumentException>({
                Joins.named("carrier-pigeon", JoinOptions(25520, 20.seconds))
            }) { e -> e.message shouldBe "join = carrier-pigeon is none of [dns, srv, static]" }
        }

        test("closing a joining releases what it opened, once asked") {
            var released = 0
            Joining(Discovery.static(), Downing.keepMajority()) { released++ }.use { released shouldBe 0 }
            released shouldBe 1
        }

        test("a list given as numbered keys, as system properties give one, is read in its numbers' order") {
            val numbered = mapOf("seeds" to mapOf("1" to "b:2", "0" to "a:1", "10" to "c:3"))
            Joins.named("static", JoinOptions(25520, 20.seconds, numbered)).use { joining ->
                joining.discovery.seeds() shouldContainExactly
                    listOf(Node("", "a", 1), Node("", "b", 2), Node("", "c", 3))
            }
            shouldThrow<IllegalArgumentException>({
                Joins.named("static", JoinOptions(25520, 20.seconds, mapOf("seeds" to mapOf("first" to "a:1")), "at"))
            }) { e -> e.message shouldBe "at.seeds is not a list" }
        }
    }
}
