package dev.surefold.app.compiler

import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.classId

/**
 * A type as the key it would be at runtime, written the way `surefold-app` writes one.
 *
 * The two have to agree exactly, because the whole claim of this plugin is that it answers what
 * `surefoldWiring` answers, only sooner. So the rendering is `KType.toString()`'s — the qualified name
 * and its arguments — and the trimming that follows is `labelOf`'s.
 */
internal fun keyOf(type: ConeKotlinType): String? {
    // A Java method's return type is flexible, and `typeOf` renders the same thing with a `!`. This
    // is the `Tracer!` that matches no `Tracer`, which surefold-app's own report exists to name — so a
    // reader that skipped it would be silent about exactly the key most worth catching.
    if (type is ConeFlexibleType) return keyOf(type.lowerBound)?.let { "$it!" }

    val named = type as? ConeClassLikeType ?: return null
    val arguments = named.typeArguments.map { argument ->
        (argument as? ConeKotlinTypeProjection)?.type?.let(::keyOf) ?: return null
    }
    val name = named.classId.asFqNameString()
    val applied = if (arguments.isEmpty()) name else "$name<${arguments.joinToString(", ")}>"
    return if (type.isMarkedNullable) "$applied?" else applied
}

// Every lower-case run, exactly as surefold-app strips one, so a generic argument loses its packages too.
private val qualifiers = Regex("""\b[a-z][A-Za-z0-9_]*(\.[a-z][A-Za-z0-9_]*)*\.""")

/** A key in the words a report uses, which is the form `Module.render` also prints. */
internal fun labelOf(key: String): String = qualifiers.replace(key, "")
