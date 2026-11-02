package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldMatch
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.shouldNotContain
import dev.surefold.junit.withClue

private class Spanner
private class Bolt

/**
 * `single` and `singleOf` are inline, so the frame above `module` is already the caller's. The claim
 * worth testing is that a wrapper in another surefold module does not get named instead of the graph.
 */
object CallSiteSuite : Suite() {
    init {
        test("a node remembers the file and line it was written on") {
            val module = single { _: Spanner -> Bolt() }

            module.nodes.values.single().site.shouldNotBeNull { site ->
                site shouldMatch Regex("""io/github/matthewjones372/surefold/app/CallSiteSuite\.kt:\d+""")
            }
        }

        test("a report names the file rather than the path the site carries for a build tool") {
            single { _: Spanner -> Bolt() }.validate().leftOrNull().shouldNotBeNull { errors ->
                val report = errors.report()

                withClue("the package is there to resolve a link, not for a person to read past") {
                    report shouldContain "CallSiteSuite.kt:"
                    report shouldNotContain "io/github/matthewjones372"
                }
            }
        }

        test("a node built through a library factory names the graph rather than the factory") {
            val module = viaFactory()

            withClue("wrapping single in a surefold factory must not name that factory's own file") {
                module.nodes.values.single().site.shouldNotBeNull { site -> site shouldContain "CallSiteSuite.kt" }
            }
        }

        test("re-keying keeps the site the recipe was written on") {
            val module = singleOf(::Bolt).boundTo<Any>()

            module.nodes.values.single().site.shouldNotBeNull { site -> site shouldContain "CallSiteSuite.kt" }
        }

        test("a missing dependency reports the site of the recipe that asked") {
            single { _: Spanner -> Bolt() }.validate().leftOrNull().shouldNotBeNull { errors ->
                val report = errors.report()

                report shouldContain "missing Spanner"
                report shouldMatch Regex("""(?s).*for Bolt\s+CallSiteSuite\.kt:\d+.*""")
            }
        }
    }
}

/** Stands in for `actor` or `migrations`: a surefold-package top-level function that is not inline. */
private fun viaFactory(): Module = single<Spanner> { Spanner() }
