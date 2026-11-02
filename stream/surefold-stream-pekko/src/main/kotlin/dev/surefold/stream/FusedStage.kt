package dev.surefold.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.FlowShape
import org.apache.pekko.stream.Inlet
import org.apache.pekko.stream.Outlet
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.stage.AbstractInHandler
import org.apache.pekko.stream.stage.AbstractOutHandler
import org.apache.pekko.stream.stage.GraphStage
import org.apache.pekko.stream.stage.GraphStageLogic

/**
 * A fused run as one Pekko stage. Pekko runs adjacent stages on one actor already, but hands each element
 * from one to the next with a push and a pull; here the steps are calls in a loop, and there is one of each.
 */
internal fun Node.Fused.fusedStage(): Flow<Any, *, NotUsed> =
    Flow.fromGraph(FusedStage(steps.map { it.body() }.toTypedArray()))

private class FusedStage(private val steps: Array<(Any) -> Any?>) : GraphStage<FlowShape<Any, Any>>() {

    private val input = Inlet.create<Any>("fused.in")
    private val output = Outlet.create<Any>("fused.out")
    private val stageShape = FlowShape.of(input, output)

    override fun shape(): FlowShape<Any, Any> = stageShape

    override fun createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        object : GraphStageLogic(stageShape) {
            init {
                setHandler(
                    input,
                    object : AbstractInHandler() {
                        override fun onPush() {
                            val out = steps.through(grab(input))
                            if (out == null) pull(input) else push(output, out)
                        }
                    },
                )
                setHandler(
                    output,
                    object : AbstractOutHandler() {
                        override fun onPull() = pull(input)
                    },
                )
            }
        }
}
