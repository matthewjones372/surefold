package dev.surefold.sns.guide

import dev.surefold.logWarn
import dev.surefold.sns.SnsPublisher
import dev.surefold.sns.SnsTopic
import dev.surefold.sns.message
import dev.surefold.sns.publishMessageBatch
import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.consume
import dev.surefold.sqs.divertLefts
import dev.surefold.sqs.runDeleting
import dev.surefold.stream.Run
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sqs.SqsClient

fun announcing(sqs: SqsClient, sns: SnsClient, orders: Queue, placed: SnsTopic): Run<Nothing, Long> =
    Sqs.consume(sqs, orders)
        .publishMessageBatch(SnsPublisher(sns)) { message -> placed.message(message.body()) }
        .divertLefts(nack = true) { failed -> logWarn("not announced: $failed") }
        .runDeleting()
