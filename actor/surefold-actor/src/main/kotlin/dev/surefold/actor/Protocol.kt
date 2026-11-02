package dev.surefold.actor

import java.lang.reflect.Modifier

/**
 * Why each message in [protocol] could not cross to another node: a function, a `var`, or a class that is neither
 * data nor an object. Empty when every one could. A field is judged by its declared type only.
 */
fun protocolFaults(protocol: Class<*>): List<String> = messages(protocol).flatMap(::faults)

inline fun <reified M : Any> protocolFaults(): List<String> = protocolFaults(M::class.java)

private fun messages(type: Class<*>): List<Class<*>> =
    if (type.isSealed) type.permittedSubclasses.flatMap(::messages) else listOf(type)

private fun faults(message: Class<*>): List<String> {
    val name = message.simpleName
    val isObject = message.declaredFields.any { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }
    // Kotlin writes both for a data class and neither for a plain one; no reflection library needed.
    val isData = message.declaredMethods.map { it.name }.containsAll(listOf("copy", "component1"))
    if (!isObject && !isData) return listOf("$name is not a data class or an object")
    return message.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
        .flatMap { field ->
            listOfNotNull(
                "$name.${field.name} holds a function".takeIf { Function::class.java.isAssignableFrom(field.type) },
                "$name.${field.name} is a var".takeUnless { Modifier.isFinal(field.modifiers) },
            )
        }
}
