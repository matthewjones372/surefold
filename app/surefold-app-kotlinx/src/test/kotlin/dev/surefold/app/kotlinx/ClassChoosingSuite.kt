package dev.surefold.app.kotlinx

import com.typesafe.config.ConfigFactory
import dev.surefold.app.Module
import dev.surefold.app.single
import dev.surefold.app.testApp
import dev.surefold.app.typesafe.configOf
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import kotlinx.serialization.Serializable

@Serializable
private data class Repo(val useCache: Boolean)

private interface Inventory

private class CachedInventory : Inventory

private class PlainInventory : Inventory

object ClassChoosingSuite : Suite() {
    init {
        test("choosing with no block reads the choice from the class") {
            val hocon = ConfigFactory.parseString("repo { useCache = true }").resolve()
            val cached: Module = single<Inventory> { CachedInventory() }
            val plain: Module = single<Inventory> { PlainInventory() }

            val app = configOf(hocon) + hocon.choosing<Repo>("repo") { repo -> if (repo.useCache) cached else plain }

            testApp(app) { inventory: Inventory -> inventory }.shouldBeInstanceOf<CachedInventory>()
            testApp(app) { repo: Repo -> repo } shouldBe Repo(useCache = true)
        }
    }
}
