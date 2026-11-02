package dev.surefold.auth

import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZoneOffset

/** Sets each of [values] in turn, an [Instant] as a `timestamptz` in UTC. */
internal fun PreparedStatement.bind(vararg values: Any?) = values.forEachIndexed { at, value ->
    setObject(at + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
}
