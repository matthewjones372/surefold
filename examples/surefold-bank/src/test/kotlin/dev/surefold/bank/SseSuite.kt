package dev.surefold.bank

import com.sun.net.httpserver.HttpServer
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [hub]'s stream served on a port of its own, as the admin's is, until [block] returns. */
private fun <A> served(hub: Hub, streaming: Streaming, block: (URI) -> A): A {
    val server = HttpServer.create(InetSocketAddress(0), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/admin/stream", streamOf(hub, streaming))
        start()
    }
    try {
        return block(URI("http://localhost:${server.address.port}/admin/stream"))
    } finally {
        server.stop(0)
    }
}

private val client = HttpClient.newHttpClient()

private fun stats(n: Int) = Event(STATS, """{"n":$n}""")

private fun member(n: Int) = Event("member", """{"n":$n}""")

object SseSuite : Suite() {
    init {
        test("each event is framed with its type, its id if it has one, and its data, and a blank line") {
            val hub = Hub()
            served(hub, Streaming(heartbeat = 1.hours)) { uri ->
                val response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofLines())
                hub.await(10.seconds) { it == 1 } shouldBe true
                hub.publish(Event(STATS, """{"node":"n2","transfersPerSecond":212}""", id = 1843))
                hub.publish(Event("member", """{"node":"n3","status":"Unreachable"}"""))

                response.headers().firstValue("Content-Type").orElse("") shouldBe "text/event-stream"
                response.body().limit(7).toList() shouldBe listOf(
                    "event: stats",
                    "id: 1843",
                    """data: {"node":"n2","transfersPerSecond":212}""",
                    "",
                    "event: member",
                    """data: {"node":"n3","status":"Unreachable"}""",
                    "",
                )
            }
        }

        test("an idle stream is sent a comment line every heartbeat") {
            served(Hub(), Streaming(heartbeat = 50.milliseconds)) { uri ->
                val lines = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofLines()).body()

                lines.limit(4).toList() shouldBe listOf(": heartbeat", "", ": heartbeat", "")
            }
        }

        test("a slow client loses its oldest stats first, and keeps every member event") {
            val stream = Stream(Streaming(capacity = 4))

            listOf(stats(1), member(1), stats(2), stats(3), member(2), stats(4), member(3)).forEach(stream::offer)

            stream.drain() shouldBe listOf(member(1), member(2), stats(4), member(3))
        }

        test("a stream whose queue stays full for its time is closed and unsubscribed") {
            val now = AtomicLong()
            val hub = Hub()
            val stream = Stream(Streaming(capacity = 2, fullFor = 10.seconds), now::get)
            hub.join(stream)
            repeat(3) { hub.publish(member(it)) }
            now.addAndGet(9.seconds.inWholeNanoseconds)
            hub.publish(member(3))
            (stream.open to hub.size) shouldBe (true to 1)

            now.addAndGet(1.seconds.inWholeNanoseconds)
            hub.publish(member(4))

            (stream.open to hub.size) shouldBe (false to 0)
            stream.drain() shouldBe listOf(member(0), member(1))
        }

        test("a client that goes away is unsubscribed once a write to it fails") {
            val hub = Hub()
            served(hub, Streaming(heartbeat = 20.milliseconds)) { uri ->
                val request = HttpRequest.newBuilder(uri).build()
                val body = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()
                hub.await(10.seconds) { it == 1 } shouldBe true
                body.read()

                body.close()

                hub.await(10.seconds) { it == 0 } shouldBe true
            }
        }
    }
}
