package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull

private class Alpha

private class Bravo

private class Charlie

private class Delta

private class Echo

private class Foxtrot

private class Golf

private class Hotel

private class India

private class Six

private class Seven

private class Eight

private class Nine

private class Whole

object AritiesSuite : Suite() {
    private val leaves =
        single<Alpha> { Alpha() } +
            single<Bravo> { Bravo() } +
            single<Charlie> { Charlie() } +
            single<Delta> { Delta() } +
            single<Echo> { Echo() } +
            single<Foxtrot> { Foxtrot() } +
            single<Golf> { Golf() } +
            single<Hotel> { Hotel() } +
            single<India> { India() }

    init {
        test("a recipe declares up to nine dependencies as parameters") {
            val module =
                leaves +
                    single { _: Alpha, _: Bravo, _: Charlie, _: Delta, _: Echo, _: Foxtrot -> Six() } +
                    single { _: Alpha, _: Bravo, _: Charlie, _: Delta, _: Echo, _: Foxtrot, _: Golf -> Seven() } +
                    single { _: Alpha, _: Bravo, _: Charlie, _: Delta, _: Echo, _: Foxtrot, _: Golf, _: Hotel ->
                        Eight()
                    } +
                    single { a: Alpha, b: Bravo, c: Charlie, d: Delta, e: Echo, f: Foxtrot, g: Golf, h: Hotel, i: India
                        ->
                        Nine().also { check(listOf(a, b, c, d, e, f, g, h, i).distinct().size == 9) }
                    } +
                    single { _: Six, _: Seven, _: Eight, _: Nine -> Whole() }

            val _ = testApp(module) { whole: Whole -> whole }

            module.validate().getOrNull().shouldNotBeNull { graph -> graph.layers.flatten().size shouldBe 14 }
        }
    }
}
