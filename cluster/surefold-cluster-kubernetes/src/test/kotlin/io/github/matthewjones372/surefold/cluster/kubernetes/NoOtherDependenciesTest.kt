package io.github.matthewjones372.surefold.cluster.kubernetes

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-cluster-kubernetes may put on a service's classpath: surefold-cluster's own, and the fabric8 client.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The fabric8 client, its model, and the JSON and YAML it reads them with.
        "kubernetes-",
        "zjsonpatch-",
        "jackson-",
        "snakeyaml-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is surefold-cluster and the fabric8 client, and nothing else`() {
        val raw = System.getProperty("surefold.cluster.kubernetes.runtimeClasspath")
        withClue("the build must pass -Dsurefold.cluster.kubernetes.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue(
            "surefold-cluster-kubernetes must stay surefold-cluster and the fabric8 client, but found: $unexpected",
        ) {
            unexpected.shouldBeEmpty()
        }
    }
}
