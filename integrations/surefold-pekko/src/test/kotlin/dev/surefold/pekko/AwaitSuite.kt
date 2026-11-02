package dev.surefold.pekko

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldThrow
import org.apache.pekko.dispatch.Futures
import scala.concurrent.Future

private class Boom : RuntimeException("boom")

object AwaitSuite : Suite() {
    init {
        test("a Scala future round-trips, value and failure") {
            val boom = Boom()
            // Pekko's Java API for a failed future: `Future.failed` is an instance method of the trait too,
            // so the companion's has no static forwarder for Kotlin to call.
            Future.successful(42).await() shouldBe 42
            shouldThrow<Boom>({ Futures.failed<Int>(boom).await() }) { thrown ->
                thrown shouldBeSameInstanceAs boom
            }
        }
    }
}
