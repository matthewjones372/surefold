package dev.surefold.tenancy.test

import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.TenantTransactions
import java.sql.Connection

/**
 * Checks that a table keeps two tenants apart, as its row-level security policy should (spec 0225). It writes rows as
 * each of [first] and [second] through [transactions], then reads as each, and throws [AssertionError] if a tenant
 * sees none of its own rows or any of the other's. [read] answers the tenant of every row it can see.
 */
fun checkIsolated(
    transactions: TenantTransactions,
    insert: (Connection, TenantId) -> Unit,
    read: (Connection) -> List<TenantId>,
    first: TenantId = TenantId("surefold-test-a"),
    second: TenantId = TenantId("surefold-test-b"),
) {
    require(first != second) { "Isolation needs two different tenants." }
    for (tenant in listOf(first, second)) transactions.transaction(tenant) { insert(it, tenant) }
    for ((reader, other) in listOf(first to second, second to first)) {
        val seen = transactions.transaction(reader) { read(it) }
        if (reader !in seen) throw AssertionError("$reader cannot see its own rows, so the check proves nothing.")
        if (other in seen) throw AssertionError("$reader can see $other's rows: ${seen.count { it == other }} of them.")
    }
}
