package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome

object StartHandler : Handler {
    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome =
        Outcome.success(notes = "Start node executed: ${node.id}")
}
