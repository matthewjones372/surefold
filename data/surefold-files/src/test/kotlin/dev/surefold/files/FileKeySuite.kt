package dev.surefold.files

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow

/** Keys checked on construction, and a tenant's keys under its own prefix (spec 0224). */
object FileKeySuite : Suite() {
    init {
        test("a key that could escape its folder, or that S3 would not take, is refused with why") {
            FileKey.of("a/../b") shouldBe Either.Left(FileError.InvalidKey("a/../b", "it has a '.' or '..' segment"))
            FileKey.of("/a") shouldBe Either.Left(FileError.InvalidKey("/a", "it starts with '/'"))
            FileKey.of("") shouldBe Either.Left(FileError.InvalidKey("", "it is empty"))
            FileKey.of("a\nb") shouldBe Either.Left(FileError.InvalidKey("a\nb", "it has a control character"))
            (FileKey.of("é".repeat(513)) is Either.Left) shouldBe true
        }

        test("an ordinary key is taken as it is") {
            (FileKey.of("tickets/2026/b-42.pdf") as Either.Right).value.let {
                it.value shouldBe "tickets/2026/b-42.pdf"
                it.name shouldBe "b-42.pdf"
                it.toString() shouldBe "tickets/2026/b-42.pdf"
            }
        }

        test("a tenant's keys sit under its prefix, and the prefix knows its own") {
            val acme = TenantPrefix("acme")
            val key = (acme.key("/tickets/1.pdf") as Either.Right).value
            key.value shouldBe "tenants/acme/tickets/1.pdf"
            (key in acme) shouldBe true
            (key in TenantPrefix("globex")) shouldBe false
            (acme.key("../globex/x") is Either.Left) shouldBe true
        }

        test("a tenant is one path segment") {
            shouldThrow<IllegalArgumentException> { TenantPrefix("a/b") }
            shouldThrow<IllegalArgumentException> { TenantPrefix("..") }
        }
    }
}
