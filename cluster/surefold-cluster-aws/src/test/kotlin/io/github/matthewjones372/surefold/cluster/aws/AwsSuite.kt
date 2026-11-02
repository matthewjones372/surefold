package io.github.matthewjones372.surefold.cluster.aws

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldNotBe
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.cluster.Joins
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse
import software.amazon.awssdk.services.ecs.EcsClient
import software.amazon.awssdk.services.ecs.model.Attachment
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse
import software.amazon.awssdk.services.ecs.model.KeyValuePair
import software.amazon.awssdk.services.ecs.model.ListTasksRequest
import software.amazon.awssdk.services.ecs.model.ListTasksResponse
import software.amazon.awssdk.services.ecs.model.Task
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient
import software.amazon.awssdk.services.servicediscovery.model.DiscoverInstancesRequest
import software.amazon.awssdk.services.servicediscovery.model.DiscoverInstancesResponse
import software.amazon.awssdk.services.servicediscovery.model.HttpInstanceSummary
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Cloud Map with one service registered, as its API answers `DiscoverInstances`. */
private class FakeCloudMap(private val registered: Map<String, Map<String, String>>) : ServiceDiscoveryClient {
    var asked: DiscoverInstancesRequest? = null

    override fun discoverInstances(request: DiscoverInstancesRequest): DiscoverInstancesResponse {
        asked = request
        val instances = registered.map { (id, attributes) ->
            HttpInstanceSummary.builder().instanceId(id).attributes(attributes).build()
        }
        return DiscoverInstancesResponse.builder().instances(instances).build()
    }

    override fun serviceName() = "servicediscovery"

    override fun close() = Unit
}

/** ECS with tasks listed two to a page, as its API pages `ListTasks`. */
private class FakeEcs(private val tasks: List<Task>) : EcsClient {
    override fun listTasks(request: ListTasksRequest): ListTasksResponse {
        val from = request.nextToken()?.toInt() ?: 0
        val page = tasks.drop(from).take(2)
        val next = (from + 2).takeIf { it < tasks.size }?.toString()
        return ListTasksResponse.builder().taskArns(page.map { it.taskArn() }).nextToken(next).build()
    }

    override fun describeTasks(request: DescribeTasksRequest): DescribeTasksResponse =
        DescribeTasksResponse.builder().tasks(tasks.filter { it.taskArn() in request.tasks() }).build()

    override fun serviceName() = "ecs"

    var closed = false

    override fun close() {
        closed = true
    }
}

private fun task(arn: String, status: String, ip: String) = Task.builder().taskArn(arn).lastStatus(status)
    .attachments(
        Attachment.builder().type("ElasticNetworkInterface")
            .details(KeyValuePair.builder().name("privateIPv4Address").value(ip).build())
            .build(),
    )
    .build()

/** One DynamoDB table, which keeps items by `name` and refuses a write whose condition does not hold. */
private class FakeDynamo : DynamoDbClient {
    val items = mutableMapOf<String, Map<String, AttributeValue>>()

    // Each write's condition, kept for the test to check once its calls return, as a check cannot fail it from here.
    private val conditions = mutableListOf<String>()

    fun everyWriteAskedFreeOrMine() = conditions.forAll { it shouldBe FREE_OR_MINE }

    override fun putItem(request: PutItemRequest): PutItemResponse {
        conditions += request.conditionExpression()
        val item = request.item()
        val values = request.expressionAttributeValues()
        val existing = items[item.getValue("name").s()]
        val free = existing == null ||
            existing.getValue("holder").s() == values.getValue(":holder").s() ||
            existing.getValue("expires").n().toLong() < values.getValue(":now").n().toLong()
        if (!free) throw ConditionalCheckFailedException.builder().message("held").build()
        items[item.getValue("name").s()] = item
        return PutItemResponse.builder().build()
    }

    override fun serviceName() = "dynamodb"

    var closed = false

    override fun close() {
        closed = true
    }
}

object AwsSuite : Suite() {
    init {
        test(
            "the instances Cloud Map has for the service are the seeds, at the port each registered or the cluster's",
        ) {
            val cloudMap = FakeCloudMap(
                mapOf(
                    "b" to mapOf("AWS_INSTANCE_IPV4" to "10.2.0.9", "AWS_INSTANCE_PORT" to "25521"),
                    "a" to mapOf("AWS_INSTANCE_IPV4" to "10.2.0.4"),
                    "c" to mapOf("AWS_INSTANCE_CNAME" to "orders.example"),
                ),
            )

            Aws.cloudMap(cloudMap, "shop.local", "orders", port = 25520).seeds() shouldContainExactly
                listOf(Node("", "10.2.0.4", 25520), Node("", "10.2.0.9", 25521))
            cloudMap.asked?.namespaceName() shouldBe "shop.local"
            cloudMap.asked?.serviceName() shouldBe "orders"
        }

        test("the running tasks of the ECS service are the seeds, across every page of them") {
            val ecs = FakeEcs(
                listOf(
                    task("t1", "RUNNING", "10.3.0.7"),
                    task("t2", "PROVISIONING", "10.3.0.8"),
                    task("t3", "RUNNING", "10.3.0.2"),
                ),
            )

            Aws.ecs(ecs, "shop", "orders", 25520).seeds() shouldContainExactly
                listOf(Node("", "10.3.0.2", 25520), Node("", "10.3.0.7", 25520))
        }

        test("an API that cannot be asked gives no seeds, and no lease") {
            val down = SdkClientException.create("unreachable")
            val cloudMap = object : ServiceDiscoveryClient {
                override fun discoverInstances(request: DiscoverInstancesRequest): DiscoverInstancesResponse =
                    throw down

                override fun serviceName() = "servicediscovery"

                override fun close() = Unit
            }
            val dynamo = object : DynamoDbClient {
                override fun putItem(request: PutItemRequest): PutItemResponse = throw down

                override fun serviceName() = "dynamodb"

                override fun close() = Unit
            }

            Aws.cloudMap(cloudMap, "shop.local", "orders", 25520).seeds().shouldBeEmpty()
            Aws.dynamoLease(dynamo, "leases", "orders").acquire("10.2.0.4") shouldBe false
        }

        test("the first to ask holds the lease, asking again renews it, and it passes on once it lapses") {
            val table = FakeDynamo()
            var at = Instant.parse("2026-09-26T10:00:00Z")
            val lease = DynamoLease(table, "leases", "orders", 15.seconds) { at }

            lease.acquire("10.2.0.4") shouldBe true
            at = at.plusSeconds(10)
            lease.acquire("10.2.0.4") shouldBe true
            lease.acquire("10.2.0.9") shouldBe false
            at = at.plusSeconds(16)
            lease.acquire("10.2.0.9") shouldBe true

            table.items.getValue("orders").getValue("holder").s() shouldBe "10.2.0.9"
            table.everyWriteAskedFreeOrMine()
        }

        test("a joining finds seeds and takes the lease through its own clients, and closing it closes both") {
            val ecs = FakeEcs(listOf(task("t1", "RUNNING", "10.3.0.7")))
            val dynamo = FakeDynamo()

            Aws.joining({ ecs }, { dynamo }, "leases", "orders", 15.seconds, 20.seconds) { client ->
                Aws.ecs(client, "shop", "orders", 25520)
            }.use { joining ->
                joining.discovery.seeds() shouldContainExactly listOf(Node("", "10.3.0.7", 25520))
                joining.downing shouldNotBe null
                (ecs.closed || dynamo.closed) shouldBe false
            }
            ecs.closed shouldBe true
            dynamo.closed shouldBe true
            dynamo.everyWriteAskedFreeOrMine()
        }

        test("join = ecs and join = cloudmap are found by their names") {
            Joins.available()["ecs"].shouldBeInstanceOf<EcsJoins>()
            Joins.available()["cloudmap"].shouldBeInstanceOf<CloudMapJoins>()
        }
    }
}
