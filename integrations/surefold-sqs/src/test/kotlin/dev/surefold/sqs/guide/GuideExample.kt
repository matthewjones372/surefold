package dev.surefold.sqs.guide

import dev.surefold.fail.Either
import dev.surefold.logWarn
import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.consume
import dev.surefold.sqs.divertLefts
import dev.surefold.sqs.mapMessage
import dev.surefold.sqs.message
import dev.surefold.sqs.runDeleting
import dev.surefold.sqs.sendTo
import dev.surefold.stream.Run
import software.amazon.awssdk.services.sqs.SqsClient

data class Order(val id: String, val customer: String)

fun shipping(
    sqs: SqsClient,
    orders: Queue,
    shipments: Queue,
    parse: (String) -> Either<String, Order>,
): Run<Nothing, Long> =
    Sqs.consume(sqs, orders)
        .mapMessage { message -> parse(message.body()) }
        .divertLefts { unreadable -> logWarn("not an order: $unreadable") }
        .sendTo(sqs) { received -> shipments.message(received.value.id, mapOf("customer" to received.value.customer)) }
        .runDeleting()
