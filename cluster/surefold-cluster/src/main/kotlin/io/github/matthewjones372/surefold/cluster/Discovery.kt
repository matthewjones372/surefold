package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.remote.Node
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Hashtable
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext

/**
 * Where a node finds the others to join through: seed nodes, and nothing more. Which nodes are members is the
 * cluster's to agree; a seed is only somewhere to ask. A seed found by host and port alone has an empty name, and is
 * whichever node answers there.
 */
fun interface Discovery {
    fun seeds(): List<Node>

    companion object {
        /** The same seeds every time: for a fixed set of hosts, and for tests. */
        fun static(vararg seeds: Node): Discovery = Discovery { seeds.toList() }

        /** The same seeds every time, each written `host:port` as config gives them; a bad one is refused now. */
        fun static(first: String, vararg rest: String): Discovery {
            val seeds = (listOf(first) + rest).map(Node::at)
            return Discovery { seeds }
        }

        /**
         * Every address [name] resolves to, each at [port]: a Kubernetes headless service, or ECS Service Connect and
         * Cloud Map, which answer a service's name with its tasks' addresses.
         */
        fun dns(name: String, port: Int, resolver: Resolver = Resolver.Jdk): Discovery = Discovery {
            resolver.addresses(name).map { Node("", it, port) }
        }

        /**
         * The targets and ports of [name]'s SRV records, lowest priority first: for a service whose port differs by
         * instance, as Cloud Map's SRV records and a Kubernetes named port give it.
         */
        fun srv(name: String, resolver: Resolver = Resolver.Jdk): Discovery = Discovery {
            resolver.services(name).sortedWith(compareBy({ it.priority }, { -it.weight }))
                .map { Node("", it.target.removeSuffix("."), it.port) }
        }
    }
}

/** One SRV record: where an instance of a service is, and how it ranks against the others. */
data class Service(val priority: Int, val weight: Int, val port: Int, val target: String)

/** DNS as discovery reads it. A name that does not resolve is no seeds, not an error: the cluster asks again. */
interface Resolver {
    fun addresses(name: String): List<String>

    fun services(name: String): List<Service>

    /** The JDK's own resolver, and its JNDI DNS provider for SRV records: nothing beside the JDK. */
    object Jdk : Resolver {
        override fun addresses(name: String): List<String> =
            try {
                InetAddress.getAllByName(name).map { it.hostAddress }
            } catch (_: UnknownHostException) {
                emptyList()
            }

        override fun services(name: String): List<Service> =
            try {
                val environment = Hashtable<String, String>().apply {
                    put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")
                }
                val records = InitialDirContext(environment).getAttributes(name, arrayOf("SRV")).get("SRV")
                (0 until (records?.size() ?: 0)).map { parse(records.get(it).toString()) }
            } catch (_: NamingException) {
                emptyList()
            }

        /** `priority weight port target`, as a zone file and JNDI both write an SRV record. */
        internal fun parse(record: String): Service {
            val (priority, weight, port, target) = record.trim().split(Regex("\\s+"))
            return Service(priority.toInt(), weight.toInt(), port.toInt(), target)
        }
    }
}
