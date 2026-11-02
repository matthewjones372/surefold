package dev.surefold.openfeature

import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.Value
import dev.surefold.Carrier

/**
 * [featureContext] carried from the thread that sent a message to the one that handles it: each scalar
 * attribute, the targeting key among them, under `openfeature.` with a tag naming its type, so `3`
 * arrives as an Int and not as `"3"`. A structure, a list or an instant has no string form both ends
 * agree on, so it is left behind.
 */
class FeatureContextCarrier : Carrier {

    override fun capture(): Map<String, String> {
        val context = featureContext.get()
        if (context.isEmpty) return emptyMap()
        return buildMap {
            context.asUnmodifiableMap().forEach { (name, value) -> tagged(value)?.let { put(PREFIX + name, it) } }
        }
    }

    override fun <A> within(carried: Map<String, String>, block: () -> A): A {
        val attributes = carried.mapNotNull { (key, tagged) ->
            if (!key.startsWith(PREFIX)) null else untagged(tagged)?.let { key.removePrefix(PREFIX) to it }
        }
        if (attributes.isEmpty()) return block()
        return featureContext.locally(ImmutableContext(attributes.toMap()), block)
    }

    private fun tagged(value: Value): String? = when (val held = value.asObject()) {
        is String -> "s:$held"
        is Boolean -> "b:$held"
        is Int -> "i:$held"
        is Long -> "l:$held"
        is Double -> "d:$held"
        else -> null
    }

    private fun untagged(tagged: String): Value? {
        val text = tagged.substring(2)
        return when (tagged.take(2)) {
            "s:" -> Value(text)
            "b:" -> Value(text.toBooleanStrict())
            "i:" -> Value(text.toInt())
            "l:" -> Value(text.toLong())
            "d:" -> Value(text.toDouble())
            else -> null
        }
    }

    private companion object {
        const val PREFIX = "openfeature."
    }
}
