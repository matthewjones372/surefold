package dev.surefold.app

/**
 * What a process was started with. A node takes one rather than calling `System.getenv`, so a test of what a service
 * does with its configuration touches no environment at all.
 */
interface Sys {

    fun env(name: String): String?

    fun property(name: String): String?

    fun env(): Map<String, String>
}

object RealSys : Sys {

    override fun env(name: String): String? = System.getenv(name)

    override fun property(name: String): String? = System.getProperty(name)

    override fun env(): Map<String, String> = System.getenv()
}

class FakeSys(
    private val env: Map<String, String>,
    private val properties: Map<String, String> = emptyMap(),
) : Sys {

    override fun env(name: String): String? = env[name]

    override fun property(name: String): String? = properties[name]

    override fun env(): Map<String, String> = env
}
