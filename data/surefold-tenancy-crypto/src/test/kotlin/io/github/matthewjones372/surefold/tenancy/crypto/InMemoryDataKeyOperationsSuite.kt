package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.Shared
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.security.ProviderException

object InMemoryDataKeyOperationsSuite : Suite() {
    init {
        test("generation returns AES256 material under exactly the supplied tenant or platform context") {
            for (entries in listOf(tenantContext, mapOf("tenant_id" to "platform"), emptyMap())) {
                val memory = DataWorld()
                val context = EncryptionContext(entries)
                val generated = memory.data.generateDataKey(memory.root, context).value()
                generated.plaintext.size shouldBe 32
                memory.data.unwrap(memory.root, generated.wrapped, context).value().toList() shouldBe
                    generated.plaintext.toList()
                val altered = EncryptionContext(entries + ("platform" to "true"))
                memory.data.unwrap(memory.root, generated.wrapped, altered) shouldBe
                    failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            }
        }

        test("generation erases untransferred plaintext on unchecked provider failure and cancellation") {
            for (failure in listOf(ProviderException("provider failed"), InterruptedException("cancelled"))) {
                val captured = Shared<ByteArray?>(null)
                val key = InMemoryWrappingKey()
                val data = InMemoryDataKeyOperations(encrypt = { _, plaintext, _ ->
                    captured.update { plaintext }
                    throw failure
                }) { _, _ -> key.right() }
                shouldThrow<Exception>({ data.generateDataKey(RootKey("root"), EncryptionContext(emptyMap())) }) {
                    (it === failure) shouldBe true
                }
                captured.peek()!!.size shouldBe 32
                captured.peek()!!.all { it == 0.toByte() } shouldBe true
                val supplied = byteArrayOf(1, 2, 3)
                shouldThrow<Exception> { data.wrap(RootKey("root"), supplied, EncryptionContext(emptyMap())) }
                supplied.toList() shouldBe listOf<Byte>(1, 2, 3)
            }
        }

        test("wrapping existing bytes retains no plaintext and hands independent arrays to callers") {
            val memory = DataWorld()
            val context = EncryptionContext(tenantContext)
            val plaintext = ByteArray(53) { it.toByte() }
            val expected = plaintext.copyOf()
            val wrapped = memory.data.wrap(memory.root, plaintext, context).value()
            plaintext.fill(0)
            val opened = memory.data.unwrap(memory.root, wrapped, context).value()
            opened.toList() shouldBe expected.toList()
            opened.fill(0)
            memory.data.unwrap(memory.root, wrapped, context).value().toList() shouldBe expected.toList()
            val generated = memory.data.generateDataKey(memory.root, context).value()
            val generatedBytes = generated.plaintext.copyOf()
            generated.plaintext.fill(0)
            memory.data.unwrap(memory.root, generated.wrapped, context).value().toList() shouldBe
                generatedBytes.toList()
        }

        test("every exact context entry and root is authenticated") {
            val memory = DataWorld()
            val wrapped = memory.data.wrap(memory.root, byteArrayOf(4, 5), EncryptionContext(tenantContext)).value()
            for ((field, replacement) in listOf("tenant_id" to "other", "purpose" to "bidx", "version" to "2")) {
                val changed = EncryptionContext(tenantContext + (field to replacement))
                memory.data.unwrap(memory.root, wrapped, changed) shouldBe
                    failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            }
            memory.data.unwrap(memory.otherRoot, wrapped, EncryptionContext(tenantContext)) shouldBe
                failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            val corrupt = wrapped.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            for (invalid in listOf(byteArrayOf(), ByteArray(12), ByteArray(27), corrupt)) {
                memory.data.unwrap(memory.root, invalid, EncryptionContext(tenantContext)) shouldBe
                    failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            }
        }

        test("context encoding distinguishes delimiter ambiguities and preserves map ordering equivalence") {
            val memory = DataWorld()
            val first = EncryptionContext(mapOf("a" to "b, c=d"))
            val second = EncryptionContext(mapOf("a" to "b", "c" to "d"))
            val wrapped = memory.data.wrap(memory.root, byteArrayOf(1), first).value()
            memory.data.unwrap(memory.root, wrapped, second) shouldBe
                failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            val equalsInKey = EncryptionContext(mapOf("a=b" to "c"))
            val equalsInValue = EncryptionContext(mapOf("a" to "b=c"))
            val otherWrapped = memory.data.wrap(memory.root, byteArrayOf(2), equalsInKey).value()
            memory.data.unwrap(memory.root, otherWrapped, equalsInValue) shouldBe
                failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            val ordered = EncryptionContext(linkedMapOf("😀" to "\u0000,={}", "" to "", "a" to "b"))
            val reordered = EncryptionContext(linkedMapOf("a" to "b", "" to "", "😀" to "\u0000,={}"))
            val orderedWrapped = memory.data.wrap(memory.root, byteArrayOf(3), ordered).value()
            memory.data.unwrap(memory.root, orderedWrapped, reordered).value().toList() shouldBe listOf<Byte>(3)
        }

        test("different UTF16 strings cannot alias through replacement encoding") {
            val memory = DataWorld()
            val context = EncryptionContext(mapOf("name" to "\uD800"))
            val wrapped = memory.data.wrap(memory.root, byteArrayOf(9), context).value()
            for (changed in listOf("?", "\uD801", "\uFFFD")) {
                memory.data.unwrap(memory.root, wrapped, EncryptionContext(mapOf("name" to changed))) shouldBe
                    failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
            }
        }

        test("related regional roots share material while unrelated roots cannot open their wrappers") {
            val shared = InMemoryWrappingKey()
            val primary = RootKey("primary/eu-west-2")
            val replica = RootKey("replica/eu-west-1")
            val unrelated = RootKey("unrelated/eu-west-2")
            val keys = Shared(mapOf(primary to shared, replica to shared, unrelated to InMemoryWrappingKey()))
            val data = InMemoryDataKeyOperations { root, operation ->
                keys.peek()[root]?.right() ?: KeyOperationFailure(operation, KeyFailureCode.NotObserved).left()
            }
            val context = EncryptionContext(tenantContext)
            val wrapped = data.wrap(primary, byteArrayOf(1), context).value()
            data.unwrap(replica, wrapped, context).value().toList() shouldBe listOf<Byte>(1)
            val replicaWrapped = data.wrap(replica, byteArrayOf(2), context).value()
            data.unwrap(primary, replicaWrapped, context).value().toList() shouldBe listOf<Byte>(2)
            data.unwrap(unrelated, wrapped, context) shouldBe
                failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
        }

        test("absent roots answer NotObserved for every operation") {
            val memory = DataWorld()
            val missing = RootKey("missing")
            val context = EncryptionContext(emptyMap())
            memory.data.generateDataKey(missing, context) shouldBe
                failure(KeyOperation.Generate, KeyFailureCode.NotObserved)
            memory.data.wrap(missing, byteArrayOf(1), context) shouldBe
                failure(KeyOperation.Wrap, KeyFailureCode.NotObserved)
            memory.data.unwrap(missing, byteArrayOf(1), context) shouldBe
                failure(KeyOperation.Unwrap, KeyFailureCode.NotObserved)
        }

        test("captured context is unaffected by later mutation of its input map") {
            val memory = DataWorld()
            val entries = tenantContext.toMutableMap()
            val captured = EncryptionContext(entries)
            val wrapped = memory.data.wrap(memory.root, byteArrayOf(7), captured).value()
            entries.clear()
            memory.data.unwrap(memory.root, wrapped, captured).value().toList() shouldBe listOf<Byte>(7)
            memory.data.unwrap(memory.root, wrapped, EncryptionContext(entries)) shouldBe
                failure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext)
        }

        test("each operation preserves root lookup failures without leaking input") {
            for (code in listOf(KeyFailureCode.NotObserved, KeyFailureCode.Refused, KeyFailureCode.Unavailable)) {
                val memory = DataWorld()
                memory.refusal.update { code }
                val context = EncryptionContext(tenantContext)
                memory.data.generateDataKey(memory.root, context) shouldBe failure(KeyOperation.Generate, code)
                memory.data.wrap(memory.root, byteArrayOf(1), context) shouldBe failure(KeyOperation.Wrap, code)
                memory.data.unwrap(memory.root, byteArrayOf(1), context) shouldBe failure(KeyOperation.Unwrap, code)
            }
        }

        test("empty plaintext round trips and repeated encryption uses fresh nonces") {
            val memory = DataWorld()
            val context = EncryptionContext(emptyMap())
            val first = memory.data.wrap(memory.root, byteArrayOf(), context).value()
            val second = memory.data.wrap(memory.root, byteArrayOf(), context).value()
            memory.data.unwrap(memory.root, first, context).value().size shouldBe 0
            first.contentEquals(second) shouldBe false
        }
    }
}

private val tenantContext = mapOf("tenant_id" to "tenant-a", "purpose" to "enc", "version" to "1")

private class DataWorld {
    val root = RootKey("memory/root")
    val otherRoot = RootKey("memory/other")
    val refusal = Shared<KeyFailureCode?>(null)
    private val roots = Shared(mapOf(root to InMemoryWrappingKey(), otherRoot to InMemoryWrappingKey()))
    val data = InMemoryDataKeyOperations { requested, operation ->
        val refused = refusal.peek()
        if (refused != null) {
            KeyOperationFailure(operation, refused).left()
        } else {
            roots.peek()[requested]?.right() ?: KeyOperationFailure(operation, KeyFailureCode.NotObserved).left()
        }
    }
}

private fun <E, A> Either<E, A>.value(): A = (this as Either.Right).value

private fun failure(operation: KeyOperation, code: KeyFailureCode) = Either.Left(KeyOperationFailure(operation, code))
