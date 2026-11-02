package io.github.matthewjones372.surefold.app.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderState
import dev.openfeature.sdk.exceptions.OpenFeatureError
import dev.surefold.Shared
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.Wiring
import io.github.matthewjones372.surefold.app.probe
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.openfeature.useSurefold
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Who holds the process-wide `OpenFeatureAPI`. Two graphs in one JVM would otherwise set each other's provider
 * and shut it down under each other, so the second is refused instead.
 */
private val holder = Shared<String?>(null, name = "surefold.app.openfeature")

/** Shuts the SDK down and lets the next graph install it, whether or not the shutdown throws. */
private fun given(api: OpenFeatureAPI) {
    try {
        api.shutdown()
    } finally {
        holder.update { null }
    }
}

/**
 * [provider] as the default domain's provider, set before any node that takes a [Client] is built, and shut down
 * when the graph is released. A provider whose init throws refuses the start. Readiness is down while the
 * provider is in error or stale, under the name `openfeature`.
 *
 * `Flag.value()` reads the same provider, since `featureClient` defaults to the default domain's client.
 */
fun openFeature(provider: () -> FeatureProvider): Module =
    single<Client> { started { provider() } }.probe<Client>("openfeature", timeout = 1.seconds, ask = ready)

/**
 * [openFeature], with a provider built from [D], a node the graph already holds, such as its `Config`. The provider
 * is built on the recipe's own [Wiring], so it can refuse the start with a reason of its own.
 */
inline fun <reified D : Any> openFeature(noinline provider: Wiring.(D) -> FeatureProvider): Module =
    single<Client, D> { dependency -> started { provider(dependency) } }
        .probe<Client>("openfeature", timeout = 1.seconds, ask = ready)

/** Ready unless the provider is in error or stale. */
@PublishedApi
internal val ready: (Client) -> Boolean = { client ->
    client.providerState != ProviderState.ERROR && client.providerState != ProviderState.STALE
}

/** Sets [provider] once this graph holds OpenFeature, and installs its shutdown. */
@PublishedApi
internal fun Wiring.started(provider: Wiring.() -> FeatureProvider): Client {
    val holding = "a graph started at ${Instant.now()} on ${Thread.currentThread()}"
    holder.modify { was -> (was ?: holding) to was }?.let { held ->
        refuse("OpenFeature is already installed in this JVM, by $held")
    }
    // Installed before the provider starts: a start that refuses or throws from here still gives OpenFeature back.
    val api = install({ OpenFeatureAPI.getInstance() }) { held, _ -> given(held) }
    api.useSurefold()
    try {
        api.setProviderAndWait(provider())
    } catch (failed: OpenFeatureError) {
        refuse("the OpenFeature provider did not start: ${failed.message}")
    }
    return api.client
}
