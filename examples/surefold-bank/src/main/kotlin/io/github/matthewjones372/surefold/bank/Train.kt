package io.github.matthewjones372.surefold.bank

import io.github.matthewjones372.surefold.actor.InMemoryJournal
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.cluster.Discovery
import io.github.matthewjones372.surefold.logInfo
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.management.ManagementFactory
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** Enough for every route a transfer takes to be compiled before the run ends (spec 0278, open question 2). */
internal const val TRAINING_TRANSFERS = 300

private const val TRAINING_ACCOUNTS = 10
private const val TRAINING_BALANCE = 1_000_000L
private const val TRAINING_MOST = 500L

/** How long this JVM took to get here: what an AOT cache is measured against (spec 0278). */
internal fun logReady() = logInfo("ready in ${ManagementFactory.getRuntimeMXBean().uptime} ms")

/**
 * `--train` (spec 0278): the run an AOT cache is made from. Three nodes in memory on ports nobody else holds, as
 * `main` starts them, then [transfers] transfers through their HTTP APIs, each waited for until some node has heard
 * it end, then everything closed. It returns rather than exits, so the JVM writes the cache as it leaves of its own
 * accord.
 */
internal fun train(transfers: Int) {
    // A transfer can end before its POST has answered, so it counts once both are known, whichever comes second.
    val ended = ConcurrentHashMap.newKeySet<String>()
    val sent = ConcurrentHashMap.newKeySet<String>()
    val counted = ConcurrentHashMap.newKeySet<String>()
    val heard = CountDownLatch(transfers)
    val countOnce = { id: String -> if (counted.add(id)) heard.countDown() }
    val ports = List(nodes.size) { ServerSocket(0).use { it.localPort } }
    val seeds = ports.map { Node("", "127.0.0.1", it) }.let { Discovery { it } }
    val journal = InMemoryJournal()
    val running = ports.mapIndexed { i, port ->
        val node = BankNode("n${i + 1}", port, seeds, journal) { id, _ ->
            ended.add(id)
            if (id in sent) countOnce(id)
        }
        node to Api(node, 0)
    }
    try {
        running.forEach { (node) ->
            check(node.members.await(1.minutes) { it.up.size == nodes.size }) { "${node.name} never saw the others" }
        }
        logReady()
        HttpClient.newHttpClient().use { client ->
            val accounts = List(TRAINING_ACCOUNTS) { "train-$it" }
            accounts.forEach {
                client.post(running[0].second, "/api/accounts", """{"id":"$it","amount":$TRAINING_BALANCE}""")
            }
            repeat(transfers) { i ->
                val from = accounts[i % accounts.size]
                val to = accounts[(i + 1) % accounts.size]
                val amount = i % TRAINING_MOST + 1
                val transfer = """{"from":"$from","to":"$to","amount":$amount}"""
                val body = client.post(running[i % running.size].second, "/api/transfers", transfer)
                val id = json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content
                sent.add(id)
                if (id in ended) countOnce(id)
            }
        }
        check(heard.await(2, TimeUnit.MINUTES)) { "${heard.count} of $transfers training transfers never ended" }
        logInfo("trained on $transfers transfers")
    } finally {
        running.forEach { (node, api) ->
            api.close()
            node.close()
        }
    }
}

private fun HttpClient.post(api: Api, path: String, body: String): String {
    val request = HttpRequest.newBuilder(URI("http://localhost:${api.port}$path"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() in 200..299) { "$path answered ${response.statusCode()}: ${response.body()}" }
    return response.body()
}
