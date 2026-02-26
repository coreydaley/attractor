package attractor.transform

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.dot.DotValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StylesheetApplicationTransformTest : FunSpec({

    test("Transform applies model_stylesheet to all nodes in the graph") {
        val graph = DotGraph("test")
        graph.attrs["model_stylesheet"] = DotValue.StringValue("* { llm_model: gpt-4o }")

        val nodeA = DotNode("a")
        val nodeB = DotNode("b")
        graph.nodes["a"] = nodeA
        graph.nodes["b"] = nodeB

        StylesheetApplicationTransform().apply(graph)

        graph.nodes["a"]!!.llmModel shouldBe "gpt-4o"
        graph.nodes["b"]!!.llmModel shouldBe "gpt-4o"
    }

    test("Transform is a no-op when model_stylesheet is blank") {
        val graph = DotGraph("test")
        val node = DotNode("a")
        graph.nodes["a"] = node

        // No model_stylesheet attr → apply is a no-op
        StylesheetApplicationTransform().apply(graph)

        graph.nodes["a"]!!.llmModel shouldBe ""
    }

    test("Existing node attributes are not overwritten by stylesheet") {
        val graph = DotGraph("test")
        graph.attrs["model_stylesheet"] = DotValue.StringValue("* { llm_model: stylesheet-model }")

        val node = DotNode("a")
        node.attrs["llm_model"] = DotValue.StringValue("explicit-model")
        graph.nodes["a"] = node

        StylesheetApplicationTransform().apply(graph)

        graph.nodes["a"]!!.llmModel shouldBe "explicit-model"
    }
})
