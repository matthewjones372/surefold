package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldNotBe
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** A directory of its own for a block, gone once the block has returned or thrown. */
class DirectoriesTest {

    @Test
    fun `the directory is empty, and is deleted with what was written into it once the block returns`() {
        var seen: Path? = null

        val answer = withTempDirectory { directory ->
            seen = directory
            Files.list(directory).use { it.count() } shouldBe 0L
            Files.writeString(Files.createDirectories(directory.resolve("nested")).resolve("a.txt"), "a")
            42
        }

        answer shouldBe 42
        Files.exists(seen!!) shouldBe false
    }

    @Test
    fun `the directory is deleted when the block throws, and the throw goes on up`() {
        var seen: Path? = null

        shouldThrow<IllegalStateException> {
            withTempDirectory { directory ->
                seen = directory
                Files.writeString(directory.resolve("a.txt"), "a")
                error("the block failed")
            }
        }

        Files.exists(seen!!) shouldBe false
    }

    @Test
    fun `two calls get two directories`() {
        val first = withTempDirectory { it }
        val second = withTempDirectory { it }

        first shouldNotBe second
    }
}
