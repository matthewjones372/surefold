package dev.surefold.auth

import javax.sql.DataSource

/**
 * Roles and permissions in two tables from the changelog `surefold/auth/postgres.sql`: a role granted to a user in a
 * tenant, or in every tenant when the tenant is null, and the permissions each role allows. Give it to
 * [PostgresSessions] and every resolved principal carries them.
 */
class PostgresRoles(private val source: DataSource) : RoleSource {

    /** Grants [role] to [user] in [tenant], or in every tenant for null; false when it was already granted. */
    fun grant(user: UserId, tenant: String?, role: String): Boolean = update(GRANT, user.value, tenant, role) == 1

    fun revoke(user: UserId, tenant: String?, role: String): Boolean =
        update(REVOKE, user.value, tenant, tenant, role) == 1

    /** Lets everyone holding [role] do [permission]; false when it already could. */
    fun allow(role: String, permission: String): Boolean = update(ALLOW, role, permission) == 1

    fun disallow(role: String, permission: String): Boolean = update(DISALLOW, role, permission) == 1

    override fun grants(user: UserId, tenant: String?): Grants = source.connection.use { connection ->
        connection.prepareStatement(GRANTS).use { statement ->
            statement.bind(user.value, tenant)
            statement.executeQuery().use { rows ->
                val roles = mutableSetOf<String>()
                val permissions = mutableSetOf<String>()
                while (rows.next()) {
                    roles += rows.getString("role")
                    rows.getString("permission")?.let(permissions::add)
                }
                Grants(roles, permissions)
            }
        }
    }

    private fun update(sql: String, vararg values: Any?): Int = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.bind(*values)
            statement.executeUpdate()
        }
    }

    private companion object {
        const val GRANT =
            "insert into surefold_role_grants (user_id, tenant_id, role) values (?, ?, ?) on conflict do nothing"

        const val REVOKE = """
            delete from surefold_role_grants
            where user_id = ? and (tenant_id = ? or (tenant_id is null and ?::text is null)) and role = ?
        """

        const val ALLOW =
            "insert into surefold_role_permissions (role, permission) values (?, ?) on conflict do nothing"

        const val DISALLOW = "delete from surefold_role_permissions where role = ? and permission = ?"

        // A grant in every tenant counts in this one; a principal with no tenant holds only those.
        const val GRANTS = """
            select g.role, p.permission
            from surefold_role_grants g left join surefold_role_permissions p on p.role = g.role
            where g.user_id = ? and (g.tenant_id is null or g.tenant_id = ?)
        """
    }
}
