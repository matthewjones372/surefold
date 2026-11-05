package dev.surefold.junit

import dev.surefold.assert.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestTag

class AspectTest {

    @Test
    fun `plus keeps both sides' tags, in order, and either side's alone`() {
        val both = tag("io") + alone + tag("slow")

        both.tags.toList() shouldBe listOf(TestTag.create("io"), TestTag.create("slow"))
        both.alone shouldBe true
        both.ignored shouldBe null
        (tag("io") + tag("slow")).alone shouldBe false
    }

    @Test
    fun `an ignore on either side ignores the test, and the outer one's reason is the one given`() {
        (tag("io") + ignore("no QUIC on CI")).ignored shouldBe "no QUIC on CI"
        (ignore("outer") + ignore("inner")).ignored shouldBe "outer"
    }

    @Test
    fun `the left side wraps the right, around the test`() {
        val seen = CopyOnWriteArrayList<String>()
        fun recording(name: String) = Aspect.around { test ->
            seen += "$name in"
            test()
            seen += "$name out"
        }

        (recording("left") + recording("middle") + recording("right")).run { seen += "test" }

        seen shouldBe listOf("left in", "middle in", "right in", "test", "right out", "middle out", "left out")
    }

    @Test
    fun `an aspect is a value, so adding to one leaves it as it was`() {
        val io = tag("io")

        (io + alone).alone shouldBe true
        io.alone shouldBe false
    }
}
