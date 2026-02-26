package attractor.style

import attractor.dot.DotNode
import attractor.dot.DotValue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StylesheetTest : FunSpec({

    fun makeNode(id: String, cssClass: String = "", vararg attrs: Pair<String, String>): DotNode {
        val node = DotNode(id)
        if (cssClass.isNotBlank()) node.attrs["class"] = DotValue.StringValue(cssClass)
        attrs.forEach { (k, v) -> node.attrs[k] = DotValue.StringValue(v) }
        return node
    }

    test("Universal selector (*) applies to all nodes") {
        val css = "* { llm_model: gpt-4o }"
        val rules = Stylesheet.parse(css)

        val nodeA = makeNode("a")
        val nodeB = makeNode("b")
        Stylesheet.applyToNode(nodeA, rules)
        Stylesheet.applyToNode(nodeB, rules)

        nodeA.llmModel shouldBe "gpt-4o"
        nodeB.llmModel shouldBe "gpt-4o"
    }

    test("Class selector (.cls) matches nodes with matching cssClass") {
        val css = ".fast { llm_model: claude-haiku }"
        val rules = Stylesheet.parse(css)

        val fastNode = makeNode("a", cssClass = "fast")
        val slowNode = makeNode("b", cssClass = "slow")
        Stylesheet.applyToNode(fastNode, rules)
        Stylesheet.applyToNode(slowNode, rules)

        fastNode.llmModel shouldBe "claude-haiku"
        slowNode.llmModel shouldBe ""  // no match
    }

    test("Id selector (#id) matches only the named node") {
        val css = "#mynode { llm_model: claude-opus }"
        val rules = Stylesheet.parse(css)

        val targeted = makeNode("mynode")
        val other = makeNode("othernode")
        Stylesheet.applyToNode(targeted, rules)
        Stylesheet.applyToNode(other, rules)

        targeted.llmModel shouldBe "claude-opus"
        other.llmModel shouldBe ""
    }

    test("Id selector overrides class selector overrides universal (specificity chain)") {
        val css = """
            * { llm_model: universal-model }
            .medium { llm_model: class-model }
            #special { llm_model: id-model }
        """.trimIndent()
        val rules = Stylesheet.parse(css)

        val specialNode = makeNode("special", cssClass = "medium")
        val classOnlyNode = makeNode("other", cssClass = "medium")
        val universalNode = makeNode("plain")

        Stylesheet.applyToNode(specialNode, rules)
        Stylesheet.applyToNode(classOnlyNode, rules)
        Stylesheet.applyToNode(universalNode, rules)

        specialNode.llmModel shouldBe "id-model"      // #id wins
        classOnlyNode.llmModel shouldBe "class-model" // .class wins over *
        universalNode.llmModel shouldBe "universal-model" // * is the only match
    }

    test("Existing node attribute is NOT overwritten by a matching stylesheet rule") {
        val css = "* { llm_model: stylesheet-model }"
        val rules = Stylesheet.parse(css)

        val nodeWithModel = makeNode("a", "", "llm_model" to "explicit-model")
        Stylesheet.applyToNode(nodeWithModel, rules)

        nodeWithModel.llmModel shouldBe "explicit-model"  // not overwritten
    }

    test("StylesheetParser handles quoted and unquoted property values") {
        val css = """
            * { llm_model: "claude-3-opus-20240229" }
            .fast { llm_model: gpt-4o }
        """.trimIndent()
        val rules = Stylesheet.parse(css)

        rules.size shouldBe 2
        val universalRule = rules.first { it.selector is StyleSelector.Universal }
        universalRule.properties["llm_model"] shouldBe "claude-3-opus-20240229"

        val classRule = rules.first { it.selector is StyleSelector.ByClass }
        classRule.properties["llm_model"] shouldBe "gpt-4o"
    }

    test("StylesheetParser returns empty list for blank input") {
        val rules = Stylesheet.parse("")
        rules.size shouldBe 0
    }

    test("StylesheetParser degrades gracefully on malformed input (missing brace)") {
        // Malformed: missing closing brace — parser should not throw
        val css = "* { llm_model: gpt-4o"
        val rules = Stylesheet.parse(css)
        // Either parses the rule partially or returns empty — no exception
        // The important thing is no crash
        rules.size shouldBe 1  // parser reads until end-of-input
    }
})
