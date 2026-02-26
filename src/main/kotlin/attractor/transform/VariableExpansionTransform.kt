package attractor.transform

import attractor.dot.DotGraph
import attractor.dot.DotValue

/**
 * Expands $goal in node prompt attributes (Section 9.2).
 */
class VariableExpansionTransform : Transform {
    override fun apply(graph: DotGraph): DotGraph {
        val goal = graph.goal
        if (goal.isEmpty()) return graph

        graph.nodes.values.forEach { node ->
            val prompt = node.attrs["prompt"]
            if (prompt is DotValue.StringValue && prompt.value.contains("\$goal")) {
                node.attrs["prompt"] = DotValue.StringValue(
                    prompt.value.replace("\$goal", goal)
                )
            }
            val label = node.attrs["label"]
            if (label is DotValue.StringValue && label.value.contains("\$goal")) {
                node.attrs["label"] = DotValue.StringValue(
                    label.value.replace("\$goal", goal)
                )
            }
        }
        return graph
    }
}
