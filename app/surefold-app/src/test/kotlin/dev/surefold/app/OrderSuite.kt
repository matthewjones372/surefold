package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue

private class Mains
private class Pumping(val mains: Mains)
private class Tap(val pumping: Pumping)
private class Kitchen(val tap: Tap, val mains: Mains)

private val backwards: Module =
    singleOf(::Kitchen) + singleOf(::Tap) + singleOf(::Pumping) + singleOf(::Mains)

private val forwards: Module =
    singleOf(::Mains) + singleOf(::Pumping) + singleOf(::Tap) + singleOf(::Kitchen)

/**
 * Nothing is declared before what it needs.
 *
 * A graph is a set keyed by type, so `plus` is commutative: the order a service writes its modules in
 * is the order a reader wants them in, not an order the wiring depends on.
 */
object OrderSuite : Suite() {
    init {
        test("a graph written consumer-first starts") {
            val kitchen = testApp(backwards) { kitchen: Kitchen -> kitchen }

            withClue("Kitchen was written before Mains existed anywhere in the file") {
                kitchen.tap.pumping.mains shouldBe kitchen.mains
            }
        }

        test("and plans the same as one written provider-first") {
            backwards.validate().getOrNull().shouldNotBeNull { one ->
                forwards.validate().getOrNull().shouldNotBeNull { other -> one.layers shouldBe other.layers }
            }
        }

        test("and draws the same") {
            backwards.render() shouldBe forwards.render()
        }
    }
}
