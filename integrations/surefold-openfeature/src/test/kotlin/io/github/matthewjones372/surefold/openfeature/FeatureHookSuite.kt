package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.Value
import dev.surefold.LogLevel
import dev.surefold.Resource
import dev.surefold.capturingLogs
import dev.surefold.capturingMetrics
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.withClue
import dev.surefold.resource

/** A provider that is down: every evaluation throws. */
private object Down : FeatureProvider {
    override fun getMetadata() = Metadata { "down" }

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, ctx: EvaluationContext) = out<Boolean>()

    override fun getStringEvaluation(key: String, defaultValue: String, ctx: EvaluationContext) = out<String>()

    override fun getIntegerEvaluation(key: String, defaultValue: Int, ctx: EvaluationContext) = out<Int>()

    override fun getDoubleEvaluation(key: String, defaultValue: Double, ctx: EvaluationContext) = out<Double>()

    override fun getObjectEvaluation(key: String, defaultValue: Value, ctx: EvaluationContext) = out<Value>()

    private fun <A> out(): ProviderEvaluation<A> = error("the flag service is down")
}

/** Spec 0180: an evaluation is counted, and one that fell back to its default says so. */
object FeatureHookSuite : SharedSuite<OpenFeatureAPI>() {
    override val shared: Resource<OpenFeatureAPI> = resource {
        OpenFeatureAPI.getInstance().apply {
            useSurefold()
            setProviderAndWait(Echo)
            setProviderAndWait("down", Down)
        }
    }

    init {
        test(
            "an evaluation against a failing provider warns once, counts one error, and answers with the default",
        ) { api ->
            val (answer, lines) = capturingLogs { logs ->
                capturingMetrics { measured ->
                    val answer = api.getClient("down").getBooleanValue("adoption.registry-check", true)
                    withClue("one reading, tagged with the flag and the error code") {
                        measured.counter("openfeature.errors") shouldBe 1.0
                        measured.tags("openfeature.errors") shouldBe
                            mapOf("flag" to "adoption.registry-check", "code" to "GENERAL")
                    }
                    answer
                } to logs.all().filter { it.level == LogLevel.Warn }
            }

            answer shouldBe true
            lines shouldHaveSize 1
            lines.single().message shouldContain "adoption.registry-check"
            lines.single().message shouldContain "GENERAL"
        }

        test("every evaluation is counted under its flag, variant and reason") { api ->
            capturingMetrics { measured ->
                val _ = evaluatedFor("ada") { api.client.getStringValue("who", "nobody") }

                measured.counter("openfeature.evaluations") shouldBe 1.0
                measured.tags("openfeature.evaluations").keys shouldBe setOf("flag", "variant", "reason")
                measured.tags("openfeature.evaluations")["flag"] shouldBe "who"
            }
        }

        test("an evaluation that answered writes no warning and counts no error") { api ->
            capturingLogs { logs ->
                capturingMetrics { measured ->
                    val _ = api.client.getStringValue("who", "nobody")
                    measured.counter("openfeature.errors") shouldBe 0.0
                }
                logs.all().filter { it.level == LogLevel.Warn } shouldHaveSize 0
            }
        }

        test("useSurefold called again adds the hook once") { api ->
            api.useSurefold()
            api.useSurefold()

            api.hooks.count { it === SurefoldFeatureHook } shouldBe 1
        }
    }
}
