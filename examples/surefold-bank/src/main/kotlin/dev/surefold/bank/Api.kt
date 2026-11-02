package dev.surefold.bank

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/** What a request comes to: a status and a JSON body. */
internal data class Answer(val status: Int, val body: JsonElement)

private inline fun <reified A> answer(status: Int, body: A) = Answer(status, json.encodeToJsonElement(body))

private val ids = Regex("[A-Za-z0-9_-]{1,64}")

private val pages = Regex("[a-z-]+\\.[a-z]+")

private val types = mapOf(
    "html" to "text/html; charset=utf-8",
    "js" to "text/javascript; charset=utf-8",
    "css" to "text/css; charset=utf-8",
)

private fun failed(status: Int, why: String) = answer(status, ErrorBody(why))

private fun <A> Either<*, A>.or(status: Int, answer: (A) -> Answer): Answer = fold({ failed(status, "$it") }, answer)

/**
 * The consumer's JSON API on one node, served by the JDK's `HttpServer` on [port], each exchange on a virtual thread
 * of its own, with the pages and the admin's stream of the node's events. [crash] ends a node in this process as a
 * crash would, for the admin's "crash" button. There is no HTTP library, so the routes are
 * a `when` over the method and the path's parts.
 */
internal class Api(
    private val node: BankNode,
    port: Int,
    streaming: Streaming = Streaming(),
    private val crash: (node: String) -> Boolean = { false },
) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/api/") { exchange -> exchange.use { respond(it, answer(it)) } }
        createContext("/") { exchange -> exchange.use(::page) }
        createContext("/admin/stream", streamOf(node.hub, streaming))
        start()
    }

    val port: Int get() = server.address.port

    private fun answer(exchange: HttpExchange): Answer {
        val path = exchange.requestURI.path.removePrefix("/api/").split("/")
        val posted = exchange.requestMethod == "POST"
        val body = if (posted) exchange.requestBody.readAllBytes().decodeToString() else ""
        val got = exchange.requestMethod == "GET" && path.size == 2
        return when {
            posted && path == listOf("accounts") -> read(body, "an account", ::open)
            posted && path == listOf("transfers") -> read(body, "a transfer", ::transfer)
            posted && path == listOf("load") -> read(body, "a load", ::load)
            posted && path == listOf("crash") -> read(body, "a crash", ::crash)
            got && path[0] == "accounts" -> account(path[1])
            got && path[0] == "transfers" -> status(path[1])
            else -> failed(404, "no route for ${exchange.requestMethod} ${exchange.requestURI.path}")
        }
    }

    /** A page or what it loads, from the jar's `web` resources; `/` is the consumer's page. */
    private fun page(exchange: HttpExchange) {
        val name = when (val path = exchange.requestURI.path.removePrefix("/")) {
            "" -> "index.html"
            "admin" -> "admin.html"
            else -> path
        }
        val type = types[name.substringAfterLast('.')]?.takeIf { pages.matches(name) }
        val bytes = type?.let { Api::class.java.getResource("/web/$name")?.readBytes() }
        if (bytes == null) respond(exchange, failed(404, "no page $name")) else respond(exchange, 200, type, bytes)
    }

    private inline fun <reified A> read(body: String, what: String, route: (A) -> Answer): Answer =
        decoded<A>(body)?.let(route) ?: failed(400, "the body is not $what")

    private fun open(body: OpenBody): Answer {
        val id = body.id.takeIf(ids::matches) ?: return failed(400, "an account id is 1 to 64 letters, digits, - or _")
        val pence = body.amount.whole()?.takeIf { it >= 0 } ?: return failed(400, "amount is a whole number")
        return node.open(id, pence).or(503) { answer(200, BalanceBody(id, it)) }
    }

    private fun account(id: String): Answer = node.statement(id).or(503) { statement ->
        if (!statement.open) return@or failed(404, "no account $id")
        val movements = statement.movements.map { MovementBody(it.transfer, it.pence) }
        answer(200, AccountBody(id, statement.balance, movements))
    }

    private fun transfer(body: TransferBody): Answer {
        val from = body.from.takeIf(ids::matches) ?: return failed(400, "from names no account")
        val to = body.to.takeIf { ids.matches(it) && it != from } ?: return failed(400, "to names no other account")
        val pence = body.amount.whole()?.takeIf { it > 0 } ?: return failed(400, "amount is more than 0")
        if (node.statement(to).getOrNull()?.open != true) return failed(404, "no account $to")
        val id = "t-${UUID.randomUUID()}"
        return node.transfer(id, from, to, pence).or(503) { answer(202, AcceptedBody(id)) }
    }

    private fun load(body: LoadBody): Answer {
        val on = body.on.flag() ?: return failed(400, "on is true or false")
        node.load(on)
        return answer(202, LoadedBody(on))
    }

    private fun crash(body: CrashBody): Answer = if (crash(body.node)) {
        answer(202, CrashedBody(body.node))
    } else {
        failed(404, "no node ${body.node} in this process")
    }

    private fun status(id: String): Answer = node.status(id).or(503) { phase ->
        if (phase ==
            Phase.New.name
        ) failed(404, "no transfer $id") else answer(200, StatusBody(id, phase))
    }

    private fun respond(exchange: HttpExchange, answer: Answer) =
        respond(exchange, answer.status, "application/json", json.encodeToString(answer.body).encodeToByteArray())

    private fun respond(exchange: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}
