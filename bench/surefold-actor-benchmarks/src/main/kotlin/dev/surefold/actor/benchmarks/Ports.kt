package dev.surefold.actor.benchmarks

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// Below the ephemeral range every OS hands outgoing connections from (32768 on Linux, 49152 on macOS and Windows), so
// a connection the benchmark opens is never given a port a node is about to bind (spec 0295).
private const val LOWEST_PORT = 20_000
private const val HIGHEST_PORT = 32_000

private val handedOut = ConcurrentHashMap.newKeySet<Int>()

/** A port nothing listens on, below the ephemeral range, and never handed out twice in this JVM. */
internal fun freePort(): Int {
    while (true) {
        val port = Random.nextInt(LOWEST_PORT, HIGHEST_PORT)
        if (handedOut.add(port) && bindable(port)) return port
    }
}

private fun bindable(port: Int): Boolean = try {
    ServerSocket(port).close()
    true
} catch (_: IOException) {
    false
}
