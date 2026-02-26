package attractor.engine

import attractor.dot.DotEdge
import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.dot.DotValue
import attractor.state.Context
import attractor.state.Outcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class EdgeSelectorTest : FunSpec({

    fun makeGraph(
        nodeId: String = "a",
        vararg edges: DotEdge
    ): Pair<DotNode, DotGraph> {
        val node = DotNode(nodeId)
        val graph = DotGraph("test")
        graph.nodes[nodeId] = node
        edges.forEach { graph.edges.add(it) }
        return Pair(node, graph)
    }

    fun makeEdge(
        from: String = "a",
        to: String,
        condition: String = "",
        label: String = "",
        weight: Int = 0
    ): DotEdge {
        val attrs = mutableMapOf<String, DotValue>()
        if (condition.isNotBlank()) attrs["condition"] = DotValue.StringValue(condition)
        if (label.isNotBlank()) attrs["label"] = DotValue.StringValue(label)
        if (weight != 0) attrs["weight"] = DotValue.IntegerValue(weight.toLong())
        return DotEdge(from, to, attrs)
    }

    test("select() returns null when node has no outgoing edges") {
        val (node, graph) = makeGraph("a")
        val result = EdgeSelector.select(node, Outcome.success(), Context(), graph)
        result.shouldBeNull()
    }

    test("Step 1 — condition match takes priority over label, suggested IDs, and weight") {
        val conditionEdge = makeEdge(to = "b", condition = "outcome=success")
        val weightEdge = makeEdge(to = "c", weight = 100)  // higher weight but no condition
        val (node, graph) = makeGraph("a", conditionEdge, weightEdge)

        val context = Context()
        val result = EdgeSelector.select(node, Outcome.success(), context, graph)
        result?.to shouldBe "b"
    }

    test("Step 2 — preferred label match used when no condition matches") {
        val labelEdge = makeEdge(to = "yes", label = "Approve")
        val otherEdge = makeEdge(to = "no", label = "Reject")
        val (node, graph) = makeGraph("a", labelEdge, otherEdge)

        val outcome = Outcome.success().withPreferredLabel("Approve")
        val result = EdgeSelector.select(node, outcome, Context(), graph)
        result?.to shouldBe "yes"
    }

    test("Step 3 — suggestedNextIds respected when preferred label does not match any edge") {
        val targetEdge = makeEdge(to = "suggested_target")
        val otherEdge = makeEdge(to = "other")
        val (node, graph) = makeGraph("a", targetEdge, otherEdge)

        val outcome = Outcome.success().withSuggestedNext("suggested_target")
        val result = EdgeSelector.select(node, outcome, Context(), graph)
        result?.to shouldBe "suggested_target"
    }

    test("Step 4/5 — unconditional edge with highest weight is selected") {
        val lowWeight = makeEdge(to = "low", weight = 1)
        val highWeight = makeEdge(to = "high", weight = 10)
        val midWeight = makeEdge(to = "mid", weight = 5)
        val (node, graph) = makeGraph("a", lowWeight, highWeight, midWeight)

        val result = EdgeSelector.select(node, Outcome.success(), Context(), graph)
        result?.to shouldBe "high"
    }

    test("Lexical tiebreak — alphabetically first to-ID wins on equal weight") {
        val edgeZ = makeEdge(to = "zebra")
        val edgeA = makeEdge(to = "alpha")
        val edgeM = makeEdge(to = "mango")
        val (node, graph) = makeGraph("a", edgeZ, edgeA, edgeM)

        val result = EdgeSelector.select(node, Outcome.success(), Context(), graph)
        result?.to shouldBe "alpha"
    }

    test("select() falls back to conditional edge when no unconditional edges exist") {
        // All edges have conditions; none match the outcome — fallback to best-by-weight
        val condEdge1 = makeEdge(to = "x", condition = "outcome=fail", weight = 5)
        val condEdge2 = makeEdge(to = "y", condition = "outcome=retry", weight = 10)
        val (node, graph) = makeGraph("a", condEdge1, condEdge2)

        // No condition matches success, no unconditional edges — falls through to fallback
        val result = EdgeSelector.select(node, Outcome.success(), Context(), graph)
        // Fallback: bestByWeightThenLexical(all edges) — condEdge2 has weight 10
        result?.to shouldBe "y"
    }

    test("normalizeLabel strips [K] prefix, K) prefix, K - prefix and lowercases") {
        EdgeSelector.normalizeLabel("[Y] Yes, deploy") shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("Y) Yes, deploy")  shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("Y - Yes, deploy") shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("Yes, deploy")     shouldBe "yes, deploy"
        EdgeSelector.normalizeLabel("  APPROVE  ")     shouldBe "approve"
    }
})
