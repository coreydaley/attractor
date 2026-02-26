package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome

object ExitHandler : Handler {
    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome =
        Outcome.success(notes = "Exit node reached: ${node.id}")
}
