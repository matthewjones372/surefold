package dev.surefold.actor.remote

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private val password = TestCertificates.password

private fun store(name: String) = TestCertificates.store(name)

private fun tlsFor(name: String, ca: String = "cluster-ca") = TestCertificates.tls(name, ca)

private fun nodeNamed(name: String) = Node(name, "127.0.0.1", freePort())

private fun frameOf(n: Int) = Frame("/user/clinic", 1, byteArrayOf(n.toByte()))

/** What a transport tells its node, as queues and a latch a test waits on. */
private class Told(dropping: Int = 0) : Listener {
    val received = LinkedBlockingQueue<Pair<String, Int>>()
    val dropped = LinkedBlockingQueue<Int>()
    val allDropped = CountDownLatch(dropping)

    override fun received(from: Node, frame: Frame) = received.put(from.name to frame.payload[0].toInt())

    override fun dropped(peer: Node, frame: Frame) {
        dropped.put(frame.payload[0].toInt())
        allDropped.countDown()
    }

    fun take(count: Int) = List(count) { checkNotNull(received.poll(1, TimeUnit.MINUTES)) }
}

object TlsSuite : Suite() {
    init {
        test("two nodes whose certificates one CA signed exchange frames both ways, in order") {
            opening {
                val n1 = nodeNamed("n1")
                val n2 = nodeNamed("n2")
                val toldN1 = Told()
                val toldN2 = Told()
                val first = Transport(n1, toldN1, tls = tlsFor("n1")).closedAfter().apply { listen() }
                val second = Transport(n2, toldN2, tls = tlsFor("n2")).closedAfter().apply { listen() }

                (1..100).forEach { first.send(n2, frameOf(it)) }
                (1..100).forEach { second.send(n1, frameOf(it)) }

                toldN2.take(100) shouldBe (1..100).map { "n1" to it }
                toldN1.take(100) shouldBe (1..100).map { "n2" to it }
            }
        }

        test("a peer another CA signed is refused both ways, and what was sent to it is dropped and said to be") {
            opening {
                val n2 = nodeNamed("n2")
                val n4 = nodeNamed("n4")
                val toldN2 = Told(dropping = 3)
                val toldN4 = Told(dropping = 3)
                val trusted = Transport(n2, toldN2, retryFrom = 1.milliseconds, tls = tlsFor("n2")).closedAfter()
                val stranger =
                    Transport(n4, toldN4, retryFrom = 1.milliseconds, tls = tlsFor("n4", "other-ca")).closedAfter()
                trusted.listen()
                stranger.listen()

                (1..3).forEach { stranger.send(n2, frameOf(it)) }
                (1..3).forEach { trusted.send(n4, frameOf(it)) }

                toldN4.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldN2.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldN4.dropped.toList() shouldContainExactly listOf(1, 2, 3)
                toldN2.dropped.toList() shouldContainExactly listOf(1, 2, 3)
                toldN2.received.isEmpty() shouldBe true
                toldN4.received.isEmpty() shouldBe true
            }
        }

        test("a peer with no certificate is refused both ways") {
            opening {
                val n1 = nodeNamed("n1")
                val plain = nodeNamed("plain")
                val toldN1 = Told(dropping = 2)
                val toldPlain = Told(dropping = 2)
                val secure = Transport(n1, toldN1, retryFrom = 1.milliseconds, tls = tlsFor("n1")).closedAfter()
                val open = Transport(plain, toldPlain, retryFrom = 1.milliseconds).closedAfter()
                secure.listen()
                open.listen()

                (1..2).forEach { open.send(n1, frameOf(it)) }
                (1..2).forEach { secure.send(plain, frameOf(it)) }

                toldPlain.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldN1.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldN1.received.isEmpty() shouldBe true
                toldPlain.received.isEmpty() shouldBe true
            }
        }

        test("a peer that speaks TLS and trusts the CA, but has no certificate of its own, is refused") {
            opening {
                val n1 = nodeNamed("n1")
                val anonymous = nodeNamed("anonymous")
                val toldAnonymous = Told(dropping = 2)
                val noKeys = KeyStore.getInstance("PKCS12").apply { load(null, null) }
                val secure = Transport(n1, Told(), tls = tlsFor("n1")).closedAfter()
                val bare = Tls.mutual(noKeys, password, store("trusts-cluster-ca"))
                val client = Transport(anonymous, toldAnonymous, retryFrom = 1.milliseconds, tls = bare).closedAfter()
                secure.listen()

                (1..2).forEach { client.send(n1, frameOf(it)) }

                toldAnonymous.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldAnonymous.dropped.toList() shouldContainExactly listOf(1, 2)
            }
        }

        test("a node with a good certificate that claims another name is refused when it connects") {
            opening {
                val n3 = nodeNamed("n3")
                val claimsN2 = nodeNamed("n2")
                val toldN3 = Told()
                val toldImpostor = Told(dropping = 2)
                Transport(n3, toldN3, tls = tlsFor("n3")).closedAfter().listen()
                val impostor =
                    Transport(claimsN2, toldImpostor, retryFrom = 1.milliseconds, tls = tlsFor("n1")).closedAfter()

                (1..2).forEach { impostor.send(n3, frameOf(it)) }

                toldImpostor.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldN3.received.isEmpty() shouldBe true
            }
        }

        test("a node with a good certificate that claims another name is refused when it is connected to") {
            opening {
                val n3 = nodeNamed("n3")
                val claimsN2 = nodeNamed("n2")
                val toldN3 = Told(dropping = 2)
                val toldImpostor = Told()
                val honest = Transport(n3, toldN3, retryFrom = 1.milliseconds, tls = tlsFor("n3")).closedAfter()
                Transport(claimsN2, toldImpostor, tls = tlsFor("n1")).closedAfter().listen()

                (1..2).forEach { honest.send(claimsN2, frameOf(it)) }

                toldN3.allDropped.await(1, TimeUnit.MINUTES) shouldBe true
                toldImpostor.received.isEmpty() shouldBe true
            }
        }

        test("a seed known only by its address is whichever node its certificate names") {
            opening {
                val n2 = nodeNamed("n2")
                val toldN2 = Told()
                Transport(n2, toldN2, tls = tlsFor("n2")).closedAfter().listen()
                val first = Transport(nodeNamed("n1"), Told(), tls = tlsFor("n1")).closedAfter()

                first.send(Node("", n2.host, n2.port), frameOf(7))

                toldN2.take(1) shouldContainExactly listOf("n1" to 7)
            }
        }
    }
}
