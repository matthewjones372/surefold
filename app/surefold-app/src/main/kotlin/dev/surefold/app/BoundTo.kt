package dev.surefold.app

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * The same recipe, keyed as [B] instead of what it builds.
 *
 * `singleOf(::PgUserRepo).boundTo<UserRepo>()` is what a graph wants to say: the constructor names the dependencies,
 * and the interface names what everything else asks for. Written as `single`, the key has to be given as a type
 * argument — and Kotlin has no partial type-argument inference, so the dependencies then have to be written out as type
 * arguments too, or cast in the body.
 */
inline fun <reified B : Any> Module.boundTo(): Module = boundTo(typeOf<B>())

@PublishedApi
internal fun Module.boundTo(key: KType): Module {
    require(nodes.size == 1) {
        "boundTo re-keys one node, and this module holds ${nodes.size}: ${nodes.keys.joinToString { labelOf(it) }}"
    }
    val node = nodes.values.single()
    return Module.of(
        mapOf(key to Node(key, node.dependencies, node.site, node.build, node.choice)),
        probes.map { p -> Probe(p.name, key, p.timeout, p.critical, p.attempts, p.interval, p.asked, p.ask) },
    )
}
