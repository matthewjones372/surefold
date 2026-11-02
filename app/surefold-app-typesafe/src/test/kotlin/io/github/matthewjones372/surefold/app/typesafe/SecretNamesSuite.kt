package io.github.matthewjones372.surefold.app.typesafe

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

object SecretNamesSuite : Suite() {
    init {
        test("the compiler checker's secret names are Secrets default's, no more and no fewer") {
            // Both are private to their files, so each is read where its class file holds it.
            val telling = Secrets::class.java.getDeclaredField("TELLING").apply { isAccessible = true }.get(null)
            val checked = Class.forName("io.github.matthewjones372.surefold.app.compiler.HandRolledKt")
                .getDeclaredField("secretNames").apply { isAccessible = true }.get(null)

            checked shouldBe telling
        }
    }
}
