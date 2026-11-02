package io.github.matthewjones372.surefold.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant

object UuidV7Suite : Suite() {
    init {
        test("an id is version 7, of the RFC's variant, and starts with its millisecond") {
            val at = Instant.parse("2026-10-26T12:00:00.123Z")
            val id = uuidV7(at)
            id.version() shouldBe 7
            id.variant() shouldBe 2
            (id.mostSignificantBits ushr 16) shouldBe at.toEpochMilli()
        }

        test("ids made in later milliseconds sort after earlier ones") {
            val start = Instant.parse("2026-10-26T12:00:00Z")
            val ids = (0L..100L).map { uuidV7(start.plusMillis(it)).toString() }
            ids.sorted() shouldBe ids
        }
    }
}
