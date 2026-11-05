package dev.surefold.actor.remote

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// Below the ephemeral range every OS hands outgoing connections from (32768 on Linux, 49152 on macOS and Windows).
private const val LOWEST = 20_000
private const val HIGHEST = 32_000

private val handedOut = ConcurrentHashMap.newKeySet<Int>()

/**
 * A port nothing listens on, for a node a test starts later. `ServerSocket(0)` answers from the ephemeral range, so
 * between its close and the node's bind an outgoing connection, of a test running alongside, can be given the same
 * port. This one is below that range, and never handed out twice in a JVM (spec 0197).
 */
fun freePort(): Int {
    while (true) {
        val port = Random.nextInt(LOWEST, HIGHEST)
        if (handedOut.add(port) && bindable(port)) return port
    }
}

private fun bindable(port: Int): Boolean =
    try {
        ServerSocket(port).close()
        true
    } catch (_: IOException) {
        false
    }
