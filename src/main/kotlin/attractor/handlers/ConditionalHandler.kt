package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome

/**
 * No-op handler for diamond-shaped conditional routing nodes (Section 4.7).
 * Actual routing is handled by the engine's edge selection algorithm.
 */
object ConditionalHandler : Handler {
    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome =
        Outcome.success(notes = "Conditional node evaluated: ${node.id}")
}
