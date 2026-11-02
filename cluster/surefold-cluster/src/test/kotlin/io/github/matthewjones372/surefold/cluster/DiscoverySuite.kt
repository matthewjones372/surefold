package io.github.matthewjones372.surefold.cluster

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import io.github.matthewjones372.surefold.actor.remote.Node

/** DNS as a test states it: a name's addresses and SRV records, and nothing for any other name. */
private class Zone(
    private val addresses: Map<String, List<String>> = emptyMap(),
    private val services: Map<String, List<Service>> = emptyMap(),
) : Resolver {
    override fun addresses(name: String) = addresses[name].orEmpty()

    override fun services(name: String) = services[name].orEmpty()
}

object DiscoverySuite : Suite() {
    init {
        test("static seeds are the nodes it was given") {
            val one = Node("orders-1", "10.0.0.1", 25520)

            Discovery.static(one).seeds() shouldContainExactly listOf(one)
        }

        test("a name's addresses are seeds at the port given, whichever node answers there") {
            val zone = Zone(addresses = mapOf("orders.default.svc.cluster.local" to listOf("10.0.0.1", "10.0.0.2")))

            Discovery.dns("orders.default.svc.cluster.local", 25520, zone).seeds() shouldContainExactly
                listOf(Node("", "10.0.0.1", 25520), Node("", "10.0.0.2", 25520))
        }

        test("SRV records are seeds at their own ports, the lowest priority and then the heaviest first") {
            val zone = Zone(
                services = mapOf(
                    "_surefold._tcp.orders.local" to listOf(
                        Service(priority = 20, weight = 5, port = 25522, target = "c.orders.local."),
                        Service(priority = 10, weight = 1, port = 25521, target = "b.orders.local."),
                        Service(priority = 10, weight = 9, port = 25520, target = "a.orders.local."),
                    ),
                ),
            )

            Discovery.srv("_surefold._tcp.orders.local", zone).seeds() shouldContainExactly listOf(
                Node("", "a.orders.local", 25520),
                Node("", "b.orders.local", 25521),
                Node("", "c.orders.local", 25522),
            )
        }

        test("a name that resolves to nothing is no seeds") {
            Discovery.dns("nowhere.invalid", 25520).seeds().shouldBeEmpty()
            Discovery.srv("_surefold._tcp.nowhere.invalid").seeds().shouldBeEmpty()
        }

        test("the JDK resolver finds localhost, and reads an SRV record as a zone file writes it") {
            Resolver.Jdk.addresses("localhost") shouldContain "127.0.0.1"
            Resolver.Jdk.parse("10 60 25520 a.orders.local.") shouldBe Service(10, 60, 25520, "a.orders.local.")
        }

        test("a node found by host and port alone has an empty name, and reads back the same") {
            Node.parse("@10.0.0.1:25520") shouldBe Node("", "10.0.0.1", 25520)
        }
    }
}
