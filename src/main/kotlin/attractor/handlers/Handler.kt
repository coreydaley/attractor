package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome

interface Handler {
    /**
     * Execute this node and return an outcome.
     * @param node      The parsed node with all attributes
     * @param context   The shared pipeline context (read/write)
     * @param graph     The full graph (for reading outgoing edges, etc.)
     * @param logsRoot  Filesystem path for this run's log directory
     */
    fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome
}
