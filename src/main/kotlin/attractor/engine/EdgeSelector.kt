package attractor.engine

import attractor.condition.ConditionEvaluator
import attractor.dot.DotEdge
import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome

/**
 * Implements the 5-step edge selection algorithm from Section 3.3.
 */
object EdgeSelector {

    fun select(node: DotNode, outcome: Outcome, context: Context, graph: DotGraph): DotEdge? {
        val edges = graph.outgoingEdges(node.id)
        if (edges.isEmpty()) return null

        // Step 1: Condition matching
        val conditionMatched = edges.filter { edge ->
            edge.condition.isNotBlank() &&
                    ConditionEvaluator.evaluate(edge.condition, outcome, context)
        }
        if (conditionMatched.isNotEmpty()) {
            return bestByWeightThenLexical(conditionMatched)
        }

        // Step 2: Preferred label match
        if (outcome.preferredLabel.isNotBlank()) {
            val normalizedPreferred = normalizeLabel(outcome.preferredLabel)
            val labelMatch = edges.firstOrNull { edge ->
                normalizeLabel(edge.label) == normalizedPreferred
            }
            if (labelMatch != null) return labelMatch
        }

        // Step 3: Suggested next IDs
        if (outcome.suggestedNextIds.isNotEmpty()) {
            for (suggestedId in outcome.suggestedNextIds) {
                val match = edges.firstOrNull { it.to == suggestedId }
                if (match != null) return match
            }
        }

        // Step 4 & 5: Weight with lexical tiebreak (unconditional edges only)
        val unconditional = edges.filter { it.condition.isBlank() }
        if (unconditional.isNotEmpty()) {
            return bestByWeightThenLexical(unconditional)
        }

        // Fallback: any edge
        return bestByWeightThenLexical(edges)
    }

    private fun bestByWeightThenLexical(edges: List<DotEdge>): DotEdge? {
        if (edges.isEmpty()) return null
        return edges.sortedWith(
            compareByDescending<DotEdge> { it.weight }
                .thenBy { it.to }
        ).first()
    }

    /**
     * Normalize a label for comparison:
     * - lowercase
     * - trim whitespace
     * - strip accelerator prefixes: [Y] , Y) , Y -
     */
    fun normalizeLabel(label: String): String {
        var result = label.lowercase().trim()
        // Strip [K] prefix
        result = result.replace(Regex("^\\[[a-z0-9]\\]\\s*"), "")
        // Strip K) prefix
        result = result.replace(Regex("^[a-z0-9]\\)\\s*"), "")
        // Strip K - prefix
        result = result.replace(Regex("^[a-z0-9] -\\s*"), "")
        return result.trim()
    }
}
