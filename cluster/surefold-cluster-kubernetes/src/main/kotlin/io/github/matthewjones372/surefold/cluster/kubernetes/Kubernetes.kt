package io.github.matthewjones372.surefold.cluster.kubernetes

import dev.surefold.actor.remote.Node
import dev.surefold.clock
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.matthewjones372.surefold.cluster.Discovery
import io.github.matthewjones372.surefold.cluster.Downing
import io.github.matthewjones372.surefold.cluster.JoinOptions
import io.github.matthewjones372.surefold.cluster.Joining
import io.github.matthewjones372.surefold.cluster.Joins
import io.github.matthewjones372.surefold.cluster.Lease
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A cluster on Kubernetes: its seeds from the pods API, and its even splits broken by a `Lease` object. */
object Kubernetes {

    /**
     * The pods in [namespace] labelled [selector] that are running, have an address and are not being deleted, each
     * at [port]. An API server that cannot be asked gives no seeds, as a DNS name that does not resolve does.
     */
    fun discovery(client: KubernetesClient, namespace: String, selector: Map<String, String>, port: Int): Discovery =
        Discovery {
            try {
                client.pods().inNamespace(namespace).withLabels(selector).list().items
                    .filter { it.status?.phase == "Running" && it.metadata?.deletionTimestamp == null }
                    .mapNotNull { pod -> pod.status?.podIP?.takeIf { it.isNotEmpty() } }
                    .sorted()
                    .map { Node("", it, port) }
            } catch (_: KubernetesClientException) {
                emptyList()
            }
        }

    /**
     * The `Lease` named [name] in [namespace]: a holder has it for [holdFor] from when it last acquired it, and every
     * acquire by the holder renews it. A write that lost a race to another holder is refused by the API server, since
     * it carries the version it read, and answers false.
     */
    fun lease(client: KubernetesClient, namespace: String, name: String, holdFor: Duration = 15.seconds): Lease =
        KubernetesLease(client, namespace, name, holdFor) { clock.get().now() }

    /**
     * Joining from inside a pod: seeds are the pods labelled [selector] at [port], and an even split goes to whoever
     * holds the `Lease` named [lease]. The client is the pod's own, from its service account, and closing the
     * joining closes it. The namespace is the pod's own unless given (spec 0096).
     */
    fun joining(
        selector: Map<String, String>,
        port: Int,
        lease: String,
        namespace: String = ownNamespace(),
        holdFor: Duration = 15.seconds,
        stableAfter: Duration = 20.seconds,
    ): Joining = joining({ KubernetesClientBuilder().build() }, selector, port, lease, namespace, holdFor, stableAfter)

    internal fun joining(
        open: () -> KubernetesClient,
        selector: Map<String, String>,
        port: Int,
        lease: String,
        namespace: String,
        holdFor: Duration,
        stableAfter: Duration,
    ): Joining {
        val client = open()
        return Joining(
            discovery(client, namespace, selector, port),
            Downing.lease(lease(client, namespace, lease, holdFor), stableAfter),
            client::close,
        )
    }

    /**
     * The namespace this pod runs in: its service account's, else `POD_NAMESPACE`. Refused when neither says, rather
     * than falling back to `default`, which is how two environments come to share one lease.
     */
    fun ownNamespace(): String = ownNamespace(SERVICE_ACCOUNT_NAMESPACE, System::getenv)

    internal fun ownNamespace(file: Path, env: (String) -> String?): String =
        file.takeIf(Files::isReadable)?.let { Files.readString(it).trim() }?.takeIf(String::isNotEmpty)
            ?: env("POD_NAMESPACE")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("no namespace: $file is not there and POD_NAMESPACE is not set")

    private val SERVICE_ACCOUNT_NAMESPACE = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace")
}

/** `join = kubernetes`: a `selector` section, a `lease` name, and optionally the `namespace`. */
class KubernetesJoins : Joins {
    override val name = "kubernetes"

    override fun joining(options: JoinOptions): Joining = Kubernetes.joining(
        selector = options.labels("selector"),
        port = options.port,
        lease = options.string("lease"),
        namespace = options.stringOrNull("namespace") ?: Kubernetes.ownNamespace(),
        stableAfter = options.stableAfter,
    )
}

internal class KubernetesLease(
    private val client: KubernetesClient,
    private val namespace: String,
    private val name: String,
    private val holdFor: Duration,
    private val now: () -> Instant,
) : Lease {

    override fun acquire(holder: String): Boolean = try {
        take(holder, ZonedDateTime.ofInstant(now(), ZoneOffset.UTC))
    } catch (_: KubernetesClientException) {
        false
    }

    private fun take(holder: String, at: ZonedDateTime): Boolean {
        val leases = client.leases().inNamespace(namespace)
        val seconds = holdFor.inWholeSeconds.toInt()
        val existing = leases.withName(name).get()
        if (existing == null) {
            val spec = LeaseSpecBuilder().withHolderIdentity(holder).withLeaseDurationSeconds(seconds)
                .withAcquireTime(at).withRenewTime(at).withLeaseTransitions(0).build()
            val lease = LeaseBuilder().withNewMetadata().withName(name).endMetadata().withSpec(spec).build()
            leases.resource(lease).create()
            return true
        }
        val spec = existing.spec
        val held = spec.holderIdentity
        val until = spec.renewTime?.plusSeconds((spec.leaseDurationSeconds ?: 0).toLong())
        val heldByAnother = held != null && held != holder
        if (heldByAnother && until?.isAfter(at) == true) return false
        val next = LeaseSpecBuilder(spec).withHolderIdentity(holder).withLeaseDurationSeconds(seconds).withRenewTime(at)
        if (held != holder) next.withAcquireTime(at).withLeaseTransitions((spec.leaseTransitions ?: 0) + 1)
        existing.spec = next.build()
        leases.resource(existing).update()
        return true
    }
}
