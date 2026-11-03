package dev.surefold.cluster.kubernetes

import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.cluster.Joins
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withTempDirectory
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.mockwebserver.Context
import io.fabric8.mockwebserver.MockWebServer
import io.fabric8.mockwebserver.ServerRequest
import io.fabric8.mockwebserver.ServerResponse
import java.nio.file.Files
import java.time.Instant
import java.util.Queue
import kotlin.time.Duration.Companion.seconds

/**
 * An API server in this JVM that keeps what it is sent, and a client of it, for one test: what
 * `@EnableKubernetesMockClient(crud = true)` gave each Jupiter test.
 */
private fun <A> withMockClient(body: (KubernetesClient) -> A): A {
    val responses = HashMap<ServerRequest, Queue<ServerResponse>>()
    val server = KubernetesMockServer(
        Context(),
        MockWebServer(),
        responses,
        KubernetesMixedDispatcher(responses),
        false,
    )
    server.init()
    try {
        return server.createClient().use(body)
    } finally {
        server.destroy()
    }
}

private fun KubernetesClient.pod(name: String, ip: String?, phase: String, app: String = "orders") {
    val pod = PodBuilder()
        .withNewMetadata().withName(name).withNamespace("shop").addToLabels("app", app).endMetadata()
        .withNewStatus().withPhase(phase).withPodIP(ip).endStatus()
        .build()
    val _ = pods().inNamespace("shop").resource(pod).create()
}

private val start: Instant = Instant.parse("2026-09-26T10:00:00Z")

object KubernetesSuite : Suite() {
    init {
        test("the running pods with the service's labels and an address are the seeds, at the cluster's port") {
            withMockClient { client ->
                client.pod("orders-1", "10.1.0.7", "Running")
                client.pod("orders-0", "10.1.0.5", "Running")
                client.pod("orders-2", null, "Pending")
                client.pod("orders-3", "10.1.0.9", "Succeeded")
                client.pod("billing-0", "10.1.0.6", "Running", app = "billing")

                Kubernetes.discovery(client, "shop", mapOf("app" to "orders"), 25520).seeds() shouldContainExactly
                    listOf(Node("", "10.1.0.5", 25520), Node("", "10.1.0.7", 25520))
            }
        }

        test("an API server that cannot be asked gives no seeds, and no lease") {
            val nowhere = freePort()
            val config =
                ConfigBuilder().withMasterUrl("http://127.0.0.1:$nowhere").withRequestRetryBackoffLimit(0).build()
            KubernetesClientBuilder().withConfig(config).build().use { unreachable ->
                Kubernetes.discovery(unreachable, "shop", mapOf("app" to "orders"), 25520).seeds().shouldBeEmpty()
                Kubernetes.lease(unreachable, "shop", "orders").acquire("orders-0") shouldBe false
            }
        }

        test("the first to ask holds the lease, and asking again renews it for the same holder only") {
            withMockClient { client ->
                var at = start
                val lease = KubernetesLease(client, "shop", "orders", 15.seconds) { at }

                lease.acquire("10.1.0.5") shouldBe true
                at = at.plusSeconds(10)
                lease.acquire("10.1.0.5") shouldBe true
                lease.acquire("10.1.0.9") shouldBe false

                val held = client.leases().inNamespace("shop").withName("orders").get().spec
                held.holderIdentity shouldBe "10.1.0.5"
                held.renewTime.toInstant() shouldBe at
                held.leaseTransitions shouldBe 0
            }
        }

        test("a lease its holder stopped renewing passes to the next to ask") {
            withMockClient { client ->
                var at = start
                val lease = KubernetesLease(client, "shop", "orders", 15.seconds) { at }
                lease.acquire("10.1.0.5") shouldBe true

                at = at.plusSeconds(16)

                lease.acquire("10.1.0.9") shouldBe true
                lease.acquire("10.1.0.5") shouldBe false
                client.leases().inNamespace("shop").withName("orders").get().spec.leaseTransitions shouldBe 1
            }
        }

        test("a joining finds the pods and takes the lease through one client, and closing it closes the client") {
            withMockClient { client ->
                client.pod("orders-0", "10.1.0.5", "Running")
                var own: KubernetesClient? = null
                val joining = Kubernetes.joining(
                    { KubernetesClientBuilder().withConfig(client.configuration).build().also { own = it } },
                    mapOf("app" to "orders"), 25520, "orders", "shop", 15.seconds, 20.seconds,
                )
                joining.use {
                    it.discovery.seeds() shouldContainExactly listOf(Node("", "10.1.0.5", 25520))
                    Kubernetes.lease(own!!, "shop", "orders").acquire("10.1.0.5") shouldBe true
                }
                shouldThrow<IllegalStateException> { own!!.pods().inNamespace("shop").list() }
            }
        }

        test("the namespace is the service account's, else POD_NAMESPACE, else refused") {
            withTempDirectory { dir ->
                val file = dir.resolve("namespace")
                Kubernetes.ownNamespace(file) { "from-env" } shouldBe "from-env"
                val _ = Files.writeString(file, "shop\n")
                Kubernetes.ownNamespace(file) { "from-env" } shouldBe "shop"
                shouldThrow<IllegalArgumentException> { Kubernetes.ownNamespace(dir.resolve("none")) { null } }
            }
        }

        test("join = kubernetes is found by its name") {
            Joins.available()["kubernetes"].shouldBeInstanceOf<KubernetesJoins>()
        }
    }
}
