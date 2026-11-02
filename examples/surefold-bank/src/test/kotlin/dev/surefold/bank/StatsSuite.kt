package dev.surefold.bank

import dev.surefold.actor.remote.outbox
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

/** A JSON object's fields, a string's content and any other value as written. */
private fun fields(data: String): Map<String, String> =
    json.parseToJsonElement(data).jsonObject.mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: "$value" }

/** Each event on an SSE stream as its type and its data's fields, read as the lines arrive. */
internal fun events(lines: Iterator<String>): Iterator<Pair<String, Map<String, String>>> = iterator {
    var type = ""
    var data = ""
    lines.forEach { line ->
        when {
            line.startsWith("event: ") -> type = line.removePrefix("event: ")
            line.startsWith("data: ") -> data = line.removePrefix("data: ")
            line.isEmpty() && type.isNotEmpty() -> yield(type to fields(data)).also { type = "" }
        }
    }
}

object StatsSuite : Suite() {
    init {
        test("node 1's stream carries stats from all three nodes, then a member event once n3 has crashed") {
            val nodes = threeNodes()
            val api = Api(nodes[0], 0)
            try {
                val uri = URI("http://localhost:${api.port}/admin/stream")
                val lines = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).build(), BodyHandlers.ofLines())
                val stream = events(lines.body().iterator())

                val heard = stream.asSequence().filter { (type) -> type == STATS }
                    .scan(emptySet<String>()) { from, (_, stats) -> from + stats["node"].orEmpty() }
                    .first { it.size == 3 }
                heard shouldBe setOf("n1", "n2", "n3")

                nodes[2].close()

                val (_, member) = stream.asSequence().first { (type, data) -> type == "member" && data["node"] == "n3" }
                member["status"] shouldBe "Unreachable"
            } finally {
                api.close()
                nodes.forEach(BankNode::close)
            }
        }

        test("stats and events cross the wire as they were, and are written as the admin page reads them") {
            val stats = NodeStats("n2", "2026-09-27T20:00:00Z", 212, 3, 41, 34, 1209, 7, 0)
            val ended = BankEvent.Ended("t-1", "a-12", "a-40", 250, "Done", 18)
            NodeStatsCodec.outbox().run { decode(encode(stats)) } shouldBe stats
            val events = BankEventCodec.outbox()
            listOf(ended, BankEvent.Member("n3", "Unreachable")).forAll {
                events.decode(events.encode(it)) shouldBe it
            }

            stats.json() shouldBe
                """{"node":"n2","at":"2026-09-27T20:00:00Z","transfersPerSecond":212,"refused":3,""" +
                """"p99Ms":41,"shards":34,"entities":1209,"unconfirmed":7,"deadLetters":0}"""
            ended.event() shouldBe Event(
                "transfer",
                """{"id":"t-1","from":"a-12","to":"a-40","amount":250,"outcome":"Done","ms":18}""",
            )
        }
    }
}
