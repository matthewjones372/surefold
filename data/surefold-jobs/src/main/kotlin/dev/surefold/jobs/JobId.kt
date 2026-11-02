package dev.surefold.jobs

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

private const val VERSION_7 = 0x7000L
private const val RAND_A = 0xFFFL
private const val TIME_SHIFT = 16
private const val RAND_B = 0x3FFF_FFFF_FFFF_FFFFL

/**
 * A UUIDv7 (RFC 9562) for [at]: its first 48 bits are the milliseconds, so ids sort roughly by when they were made
 * and land at the end of the primary key's index. Made here because Postgres before 18 has no generator.
 */
internal fun uuidV7(at: Instant): UUID {
    val random = ThreadLocalRandom.current()
    val most = (at.toEpochMilli() shl TIME_SHIFT) or VERSION_7 or (random.nextLong() and RAND_A)
    // The variant is the top two bits, 10.
    val least = (random.nextLong() and RAND_B) or Long.MIN_VALUE
    return UUID(most, least)
}
