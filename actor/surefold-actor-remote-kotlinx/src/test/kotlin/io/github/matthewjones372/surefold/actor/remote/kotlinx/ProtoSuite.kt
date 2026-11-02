package io.github.matthewjones372.surefold.actor.remote.kotlinx

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import surefold.kotlinx.TillOuterClass
import java.io.File

/**
 * What `proto()` writes is pinned as `src/test/proto/surefold/kotlinx/till.proto`, which protoc compiles for this test:
 * the classes it generates read what a table writes, and write what a table reads.
 */
object ProtoSuite : Suite() {
    init {
        test("the proto a table makes is the golden file protoc compiles") {
            val path: String? = System.getProperty("surefold.actor.remote.kotlinx.golden")
            if (path == null) {
                withClue("the build must pass -Dsurefold.actor.remote.kotlinx.golden; see its build.gradle.kts") {
                    path.shouldNotBeNull()
                }
            } else {
                withClue(
                    "regenerate the golden file from Kotlinx.proto(\"surefold.kotlinx\", tills) " +
                        "if this change is meant",
                ) {
                    Kotlinx.proto("surefold.kotlinx", tills) shouldBe File(path).readText()
                }
            }
        }

        test("what a table writes, protoc's classes read") {
            val paid = TillOuterClass.Till.parseFrom(tills.write(Till.Paid(Pence(250), "order-42")))
            val refunded = TillOuterClass.Till.parseFrom(tills.write(Till.Refunded(Pence(100), Reason.Unwanted)))

            paid.hasPaid() shouldBe true
            paid.paid.amount shouldBe 250L
            paid.paid.reference shouldBe "order-42"
            refunded.refunded.reason shouldBe TillOuterClass.Reason.Unwanted
            TillOuterClass.Till.parseFrom(tills.write(Till.Closed)).hasClosed() shouldBe true
        }

        test("what protoc's classes write, a table reads") {
            val written = TillOuterClass.Till.newBuilder()
                .setRefunded(TillOuterClass.Refunded.newBuilder().setAmount(75).setReason(TillOuterClass.Reason.Faulty))
                .build()
                .toByteArray()

            tills.read(written) shouldBe Till.Refunded(Pence(75), Reason.Faulty)
        }
    }
}
