package dev.surefold.app.flagd

import com.typesafe.config.Config
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigUtil
import dev.openfeature.contrib.providers.flagd.FlagdOptions
import dev.openfeature.contrib.providers.flagd.FlagdProvider
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.QueuePayload
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.QueuePayloadType
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.QueueSource
import dev.openfeature.contrib.tools.flagd.core.model.FeatureFlag
import dev.openfeature.contrib.tools.flagd.core.model.FlagParser
import dev.openfeature.sdk.Value
import dev.surefold.app.Module
import dev.surefold.app.openfeature.openFeature
import dev.surefold.openfeature.Flag
import java.math.BigInteger
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import dev.openfeature.contrib.providers.flagd.Config as FlagdConfig

/**
 * The flags at [path] of the graph's `Config`, written in flagd's schema, evaluated in-process by flagd and set as
 * the graph's OpenFeature provider. HOCON is a superset of JSON, so a flagd definition is already a valid section;
 * a test overrides it like any other. A section flagd rejects refuses the start, naming where it came from.
 */
fun flagdFlags(path: String): Module = flagdFlags(path, emptyList())

/**
 * [flagdFlags], checking first that every variant of each of [declared] reads as the type it was declared with, and
 * that each is in the section at all. Every miss is named in one refusal, with the line it came from.
 */
fun flagdFlags(path: String, declared: List<Flag<*>>): Module = openFeature { config: Config ->
    if (!config.hasPath(path)) refuse("there is no flags section at $path")
    val origin = config.getValue(path).origin().description()
    val flags = """{"flags":${config.getConfig(path).root().render(ConfigRenderOptions.concise())}}"""
    val parsed = try {
        FlagParser.parseString(flags, true).flags
    } catch (rejected: IllegalArgumentException) {
        refuse("flagd rejected the flags at $path ($origin): ${rejected.message}")
    }
    val misses = declared.flatMap { flag -> misses(flag, parsed[flag.key], config, path, origin) }
    if (misses.isNotEmpty()) {
        refuse("flags at $path do not match their declarations:" + misses.joinToString("") { "\n  $it" })
    }
    FlagdProvider(
        FlagdOptions.builder().resolverType(FlagdConfig.Resolver.IN_PROCESS).customConnector(Once(flags)).build(),
    )
}

/** What is wrong with [flag]'s variants as flagd parsed them, each naming the line its variant is written on. */
private fun misses(flag: Flag<*>, parsed: FeatureFlag?, config: Config, path: String, origin: String): List<String> {
    if (parsed == null) return listOf("${flag.key}: declared but not in the section ($origin)")
    return parsed.variants.mapNotNull { (name, variant) ->
        val written = config.getValue(ConfigUtil.joinPath(ConfigUtil.splitPath(path) + flag.key + "variants" + name))
        val line = written.origin().description()
        why(flag, variant)?.let { "${flag.key}: variant $name $it ($line)" }
    }
}

/**
 * Why [flag] would not read [variant] as flagd holds it. flagd reads a whole number too big for an `Int` as none of
 * its numeric types, so a flag that reads numbers cannot hold one there, whatever the SDK's types allow.
 */
private fun why(flag: Flag<*>, variant: Any?): String? =
    if ((variant is Long || variant is BigInteger) && flag.rejects(Value(0)) == null) {
        "$variant is too big for flagd to read as a number"
    } else {
        flag.rejects(if (variant is BigInteger) Value(variant.toDouble()) else Value.objectToValue(variant))
    }

/** What flagd's in-process resolver reads its flags from: these, once, since a change is a redeploy. */
private class Once(flags: String) : QueueSource {
    private val queue = LinkedBlockingQueue(listOf(QueuePayload(QueuePayloadType.DATA, flags)))

    override fun init() = Unit

    override fun getStreamQueue(): BlockingQueue<QueuePayload> = queue

    override fun shutdown() {
        queue.offer(QueuePayload.SHUTDOWN)
    }
}
