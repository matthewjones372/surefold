package dev.surefold.auth

/** A user, as the service names it. This module keeps sessions, credentials and roles against it, and no user table. */
@JvmInline
value class UserId(val value: String) {
    override fun toString(): String = value
}

/**
 * Who a resolved session is: its [user], the [tenant] it was signed in to, or null for none, and what [roles] they
 * hold there with the [permissions] those allow.
 */
data class Principal(
    val user: UserId,
    val tenant: String?,
    val roles: Set<String> = emptySet(),
    val permissions: Set<String> = emptySet(),
) {
    fun can(permission: String): Boolean = permission in permissions
}

/** The roles a user holds in a tenant, and the permissions those allow. */
data class Grants(val roles: Set<String>, val permissions: Set<String>) {
    companion object {
        val none = Grants(emptySet(), emptySet())
    }
}

/** Where a principal's roles come from: this module's own tables, or a service's that keeps them elsewhere. */
fun interface RoleSource {
    fun grants(user: UserId, tenant: String?): Grants

    companion object {
        /** No roles for anyone: a service that checks only who is signed in. */
        val none = RoleSource { _, _ -> Grants.none }
    }
}
