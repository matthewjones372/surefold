package dev.surefold.cluster.aws

import dev.surefold.actor.remote.Node
import dev.surefold.clock
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Downing
import dev.surefold.cluster.JoinOptions
import dev.surefold.cluster.Joining
import dev.surefold.cluster.Joins
import dev.surefold.cluster.Lease
import software.amazon.awssdk.core.SdkClient
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.DesiredStatus
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient
import software.amazon.awssdk.services.servicediscovery.model.HealthStatusFilter
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** ECS describes at most this many tasks a call. */
private const val DESCRIBE_AT_MOST = 100

/** The condition a lease write carries: nobody holds it, this holder does, or its holder let it lapse. */
internal const val FREE_OR_MINE = "attribute_not_exists(#name) OR #holder = :holder OR #expires < :now"

/** A cluster on AWS: its seeds from Cloud Map or ECS, and its even splits broken by a DynamoDB item. */
object Aws {

    /**
     * The instances registered for [service] in the Cloud Map [namespace], healthy ones only when any are, at the
     * address and port each registered, or at [port] for one that registered none. An API that cannot be asked gives
     * no seeds, as a DNS name that does not resolve does.
     */
    fun cloudMap(client: ServiceDiscoveryClient, namespace: String, service: String, port: Int? = null): Discovery =
        Discovery {
            asked {
                client.discoverInstances {
                    it.namespaceName(namespace).serviceName(service)
                        .healthStatus(HealthStatusFilter.HEALTHY_OR_ELSE_ALL)
                }.instances().mapNotNull { instance ->
                    val attributes = instance.attributes()
                    val at = attributes["AWS_INSTANCE_PORT"]?.toIntOrNull() ?: port
                    attributes["AWS_INSTANCE_IPV4"]?.let { ip -> at?.let { Node("", ip, it) } }
                }
            }
        }

    /** The running tasks of the ECS [service] in [cluster], each at its network interface's address and [port]. */
    fun ecs(client: EcsClient, cluster: String, service: String, port: Int): Discovery = Discovery {
        asked {
            val arns = client.listTasksPaginator {
                it.cluster(cluster).serviceName(service).desiredStatus(DesiredStatus.RUNNING)
            }.taskArns().toList()
            arns.chunked(DESCRIBE_AT_MOST)
                .flatMap { some -> client.describeTasks { it.cluster(cluster).tasks(some) }.tasks() }
                .filter { it.lastStatus() == "RUNNING" }
                .flatMap { task -> task.attachments().filter { it.type() == "ElasticNetworkInterface" } }
                .mapNotNull { eni -> eni.details().firstOrNull { it.name() == "privateIPv4Address" }?.value() }
                .map { Node("", it, port) }
        }
    }

    /**
     * The item [name] in the DynamoDB [table], keyed by a string attribute `name`: a holder has it for [holdFor] from
     * when it last acquired it, and every acquire by the holder renews it. Each write is conditional, so of two
     * holders racing for a free lease only one gets it.
     */
    fun dynamoLease(client: DynamoDbClient, table: String, name: String, holdFor: Duration = 15.seconds): Lease =
        DynamoLease(client, table, name, holdFor) { clock.get().now() }

    /**
     * Joining an ECS service: its running tasks at [port] are the seeds, and an even split goes to whoever holds the
     * item [lease] in the DynamoDB [table]. Both clients are the SDK's defaults, from the task's own role and region,
     * and closing the joining closes them (spec 0096).
     */
    fun ecs(
        cluster: String,
        service: String,
        port: Int,
        table: String,
        lease: String,
        holdFor: Duration = 15.seconds,
        stableAfter: Duration = 20.seconds,
    ): Joining = joining(EcsClient::create, DynamoDbClient::create, table, lease, holdFor, stableAfter) {
        ecs(it, cluster, service, port)
    }

    /** Joining through the Cloud Map [service] in [namespace], as [ecs] does through an ECS service. */
    fun cloudMap(
        namespace: String,
        service: String,
        port: Int?,
        table: String,
        lease: String,
        holdFor: Duration = 15.seconds,
        stableAfter: Duration = 20.seconds,
    ): Joining = joining(ServiceDiscoveryClient::create, DynamoDbClient::create, table, lease, holdFor, stableAfter) {
        cloudMap(it, namespace, service, port)
    }

    internal fun <C : SdkClient> joining(
        open: () -> C,
        openDynamo: () -> DynamoDbClient,
        table: String,
        lease: String,
        holdFor: Duration,
        stableAfter: Duration,
        discovery: (C) -> Discovery,
    ): Joining {
        val finder = open()
        val dynamo = try {
            openDynamo()
        } catch (failed: SdkException) {
            finder.close()
            throw failed
        }
        return Joining(discovery(finder), Downing.lease(dynamoLease(dynamo, table, lease, holdFor), stableAfter)) {
            finder.use { dynamo.close() }
        }
    }
}

/** `join = ecs`: the `cluster` and `service`, and the DynamoDB `table` and `lease` that break an even split. */
class EcsJoins : Joins {
    override val name = "ecs"

    override fun joining(options: JoinOptions): Joining = Aws.ecs(
        options.string("cluster"),
        options.string("service"),
        options.port,
        options.string("table"),
        options.string("lease"),
        stableAfter = options.stableAfter,
    )
}

/** `join = cloudmap`: the Cloud Map `namespace` and `service`, and the DynamoDB `table` and `lease`. */
class CloudMapJoins : Joins {
    override val name = "cloudmap"

    override fun joining(options: JoinOptions): Joining = Aws.cloudMap(
        options.string("namespace"),
        options.string("service"),
        options.port,
        options.string("table"),
        options.string("lease"),
        stableAfter = options.stableAfter,
    )
}

private fun asked(seeds: () -> List<Node>): List<Node> = try {
    seeds().sortedWith(compareBy({ it.host }, { it.port }))
} catch (_: SdkException) {
    emptyList()
}

internal class DynamoLease(
    private val client: DynamoDbClient,
    private val table: String,
    private val name: String,
    private val holdFor: Duration,
    private val now: () -> Instant,
) : Lease {

    override fun acquire(holder: String): Boolean {
        val at = now().toEpochMilli()
        return try {
            client.putItem {
                it.tableName(table)
                    .item(
                        mapOf(
                            "name" to AttributeValue.fromS(name),
                            "holder" to AttributeValue.fromS(holder),
                            "expires" to AttributeValue.fromN((at + holdFor.inWholeMilliseconds).toString()),
                        ),
                    )
                    .conditionExpression(FREE_OR_MINE)
                    .expressionAttributeNames(mapOf("#name" to "name", "#holder" to "holder", "#expires" to "expires"))
                    .expressionAttributeValues(
                        mapOf(":holder" to AttributeValue.fromS(holder), ":now" to AttributeValue.fromN(at.toString())),
                    )
            }
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        } catch (_: SdkException) {
            false
        }
    }
}
