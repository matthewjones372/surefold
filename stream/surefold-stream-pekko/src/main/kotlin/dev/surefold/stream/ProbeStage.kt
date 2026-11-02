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
 * A probe as a Pekko stage: an element pushed through is the stage before it emitting, and a pull from
 * downstream is the demand that ends its wait. Each materialisation takes a watch of its own.
 */
internal fun Node.Probed.probeStage(): Flow<Any, *, NotUsed> = Flow.fromGraph(ProbeStage(probe))

private class ProbeStage(private val probe: Probe) : GraphStage<FlowShape<Any, Any>>() {

    private val input = Inlet.create<Any>("probe.in")
    private val output = Outlet.create<Any>("probe.out")
    private val stageShape = FlowShape.of(input, output)

    override fun shape(): FlowShape<Any, Any> = stageShape

    override fun createLogic(inheritedAttributes: Attributes): GraphStageLogic =
        object : GraphStageLogic(stageShape) {
            private val watch = probe.watch()

            init {
                setHandler(
                    input,
                    object : AbstractInHandler() {
                        override fun onPush() {
                            watch.emitted()
                            push(output, grab(input))
                        }
                    },
                )
                setHandler(
                    output,
                    object : AbstractOutHandler() {
                        override fun onPull() {
                            watch.asked()
                            pull(input)
                        }
                    },
                )
            }
        }
}
