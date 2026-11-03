package dev.surefold.sns

import dev.surefold.capturingMetrics
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.metricTagged
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import kotlin.time.Duration.Companion.hours

/** SNS refusing any entry whose body is "bad", and failing any request with a body "down". */
private class MeteredSns : SnsClient {
    override fun publish(request: PublishRequest): PublishResponse {
        if (request.message() == "down") throw AwsServiceException.builder().message("unreachable").build()
        return PublishResponse.builder().messageId("id").build()
    }

    override fun publishBatch(request: PublishBatchRequest): PublishBatchResponse {
        val (bad, good) = request.publishBatchRequestEntries().partition { it.message() == "bad" }
        return PublishBatchResponse.builder()
            .successful(good.map { PublishBatchResultEntry.builder().id(it.id()).messageId("id").build() })
            .failed(bad.map { BatchResultErrorEntry.builder().id(it.id()).code("InvalidParameter").build() })
            .build()
    }

    override fun serviceName() = "sns"

    override fun close() = Unit
}

object MetersSuite : Suite() {
    private val topic = SnsTopic("arn:aws:sns:eu-west-1:123456789012:placed")

    init {
        test("a publish records what was published and how long it took, tagged with its topic") {
            val captured = capturingMetrics { captured ->
                metricTagged("service" to "shop") { SnsPublisher(MeteredSns()).publish(topic.message("a")) }
                captured
            }

            captured.counter("surefold.sns.publisher.published") shouldBe 1.0
            captured.histogram("surefold.sns.publisher.publish").size shouldBe 1
            captured.tags("surefold.sns.publisher.published") shouldBe mapOf("service" to "shop", "topic" to "placed")
        }

        test("a publish the SDK fails is a failure tagged with the exception's name") {
            val captured = capturingMetrics { captured ->
                SnsPublisher(MeteredSns()).publish(topic.message("down"))
                captured
            }

            captured.counter("surefold.sns.publisher.failures") shouldBe 1.0
            captured.tags("surefold.sns.publisher.failures")["code"] shouldBe "AwsServiceException"
        }

        test("a batch with one refused entry records one failure tagged with its code, and one request") {
            val captured = capturingMetrics { captured ->
                SnsPublisher(MeteredSns()).publishAll(listOf("a", "bad", "c").map { topic.message(it) })
                captured
            }

            captured.counter("surefold.sns.publisher.published") shouldBe 2.0
            captured.counter("surefold.sns.publisher.failures") shouldBe 1.0
            captured.tags("surefold.sns.publisher.failures")["code"] shouldBe "InvalidParameter"
            captured.histogram("surefold.sns.publisher.publish").size shouldBe 1
        }

        test("publishTo and publishBatch record to the meters in scope where they are described") {
            val captured = capturingMetrics { captured ->
                Stream.from(listOf("1", "2")).publishTo(SnsPublisher(MeteredSns())) { topic.message(it) }
                    .runCollect().run(Forks()).toCompletableFuture().join()
                Stream.from(listOf("3", "bad"))
                    .publishBatch(SnsPublisher(MeteredSns()), within = 1.hours) { topic.message(it) }
                    .runCollect().run(Forks()).toCompletableFuture().join()
                captured
            }

            captured.counter("surefold.sns.publisher.published") shouldBe 3.0
            captured.counter("surefold.sns.publisher.failures") shouldBe 1.0
        }
    }
}
