package io.github.matthewjones372.surefold.bank

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.holds
import dev.surefold.junit.timeout
import kotlin.time.Duration.Companion.seconds

private val within = LocatorAssertions.HasTextOptions().setTimeout(30_000.0)

internal object AdminPageSuite : SharedSuite<Browsed>() {
    // What the admin's crash button does here: the node goes as a crash does, on a thread of its own.
    override val shared: Resource<Browsed> = browsed { nodes ->
        { name ->
            val node = nodes.firstOrNull { it.name == name }
            node?.let { Thread.ofVirtual().start(it::close) }
            node != null
        }
    }

    init {
        test(
            "the load raises the transfers-a-second tile, and a crashed n3's row turns unreachable",
            timeout(120.seconds),
        ) { browsed ->
            val page = browsed.browser.newPage()
            page.navigate("http://localhost:${browsed.apis[0].port}/admin")
            holds { assertThat(page.locator("#node-n3 .status")).hasText("Up", within) }

            page.click("#load")
            holds { assertThat(page.locator("#load")).hasText("Stop load") }
            page.waitForFunction(
                "() => Number(document.getElementById('tps').textContent) > 0",
                null,
                Page.WaitForFunctionOptions().setTimeout(30_000.0),
            )
            page.click("#crash")

            holds { assertThat(page.locator("#node-n3 .status")).hasText("Unreachable", within) }
            page.click("#load")
            holds { assertThat(page.locator("#load")).hasText("Start load") }
        }
    }
}
