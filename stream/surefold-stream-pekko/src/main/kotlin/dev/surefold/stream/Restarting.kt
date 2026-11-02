package dev.surefold.stream

import dev.surefold.Clock
import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import dev.surefold.Schedule
import dev.surefold.ScheduleStep
import org.apache.pekko.NotUsed
import org.apache.pekko.japi.pf.PFBuilder
import org.apache.pekko.stream.Graph
import org.apache.pekko.stream.SourceShape
import org.apache.pekko.stream.javadsl.Source
import kotlin.time.Duration
import kotlin.time.toJavaDuration

internal class Restarts(private val to: Logger, private val by: Clock) {

    fun warn(defect: Throwable, delay: Duration) =
        to.log(
            LogLine(
                LogLevel.Warn,
                "surefold-stream: restarting in $delay after $defect",
                by.now(),
                defect,
                origin = Restarts::class.java.name,
            ),
        )
}

internal fun <A : Any> restarting(
    source: Source<A, NotUsed>,
    step: ScheduleStep<Throwable, *>,
    restarts: Restarts,
): Source<A, NotUsed> =
    source.recoverWithRetries(
        1,
        PFBuilder<Throwable, Graph<SourceShape<A>, NotUsed>>()
            .match(Throwable::class.java, { thrown -> thrown !is DeclaredFailure }) { defect ->
                when (val decision = step(defect)) {
                    is Schedule.Decision.Continue -> {
                        restarts.warn(defect, decision.delay)
                        restarting(source, decision.step, restarts).initialDelay(decision.delay.toJavaDuration())
                    }

                    is Schedule.Decision.Done -> Source.failed(defect)
                }
            }
            .build(),
    )
