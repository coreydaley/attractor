package attractor.transform

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.dot.DotValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VariableExpansionTransformTest : FunSpec({

    fun makeGraph(goal: String, vararg nodes: DotNode): DotGraph {
        val graph = DotGraph("test")
        if (goal.isNotBlank()) graph.attrs["goal"] = DotValue.StringValue(goal)
        nodes.forEach { graph.nodes[it.id] = it }
        return graph
    }

    fun makeNode(id: String, prompt: String = "", label: String = ""): DotNode {
        val node = DotNode(id)
        if (prompt.isNotBlank()) node.attrs["prompt"] = DotValue.StringValue(prompt)
        if (label.isNotBlank()) node.attrs["label"] = DotValue.StringValue(label)
        return node
    }

    test("\$goal in prompt is replaced with the graph goal value") {
        val node = makeNode("step1", prompt = "Please accomplish: \$goal")
        val graph = makeGraph("write a blog post", node)

        VariableExpansionTransform().apply(graph)

        graph.nodes["step1"]!!.prompt shouldBe "Please accomplish: write a blog post"
    }

    test("\$goal in label is replaced with the graph goal value") {
        val node = makeNode("step1", label = "Task: \$goal")
        val graph = makeGraph("build a feature", node)

        VariableExpansionTransform().apply(graph)

        graph.nodes["step1"]!!.label shouldBe "Task: build a feature"
    }

    test("Graph with empty goal is returned unchanged (no-op)") {
        val node = makeNode("step1", prompt = "Accomplish: \$goal")
        val graph = DotGraph("test")  // no goal attr
        graph.nodes["step1"] = node

        VariableExpansionTransform().apply(graph)

        // No substitution should have occurred
        graph.nodes["step1"]!!.prompt shouldBe "Accomplish: \$goal"
    }

    test("Node without \$goal in prompt is unchanged") {
        val node = makeNode("step1", prompt = "Do the regular work")
        val graph = makeGraph("my goal", node)

        VariableExpansionTransform().apply(graph)

        graph.nodes["step1"]!!.prompt shouldBe "Do the regular work"
    }

    test("Multiple \$goal occurrences in a single prompt are all replaced") {
        val node = makeNode("step1", prompt = "First: \$goal. Then again: \$goal. Done.")
        val graph = makeGraph("run tests", node)

        VariableExpansionTransform().apply(graph)

        graph.nodes["step1"]!!.prompt shouldBe "First: run tests. Then again: run tests. Done."
    }
})
