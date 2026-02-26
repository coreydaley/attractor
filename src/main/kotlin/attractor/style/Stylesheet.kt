package attractor.style

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.dot.DotValue

/**
 * Parses and applies CSS-like model stylesheets per Section 8 of the spec.
 *
 * Grammar:
 *   Stylesheet    ::= Rule+
 *   Rule          ::= Selector '{' Declaration ( ';' Declaration )* ';'? '}'
 *   Selector      ::= '*' | '#' Identifier | '.' ClassName
 *   Declaration   ::= Property ':' PropertyValue
 *   Property      ::= 'llm_model' | 'llm_provider' | 'reasoning_effort'
 *   PropertyValue ::= String | 'low' | 'medium' | 'high'
 */

data class StyleRule(
    val selector: StyleSelector,
    val properties: Map<String, String>
)

sealed class StyleSelector {
    /** Specificity: 0 = universal, 1 = class, 2 = id */
    abstract val specificity: Int

    object Universal : StyleSelector() {
        override val specificity = 0
        override fun matches(node: DotNode): Boolean = true
    }

    data class ByClass(val className: String) : StyleSelector() {
        override val specificity = 1
        override fun matches(node: DotNode): Boolean {
            val classes = node.cssClass.split(",").map { it.trim() }
            return className in classes
        }
    }

    data class ById(val nodeId: String) : StyleSelector() {
        override val specificity = 2
        override fun matches(node: DotNode): Boolean = node.id == nodeId
    }

    abstract fun matches(node: DotNode): Boolean
}

class StylesheetParser(private val input: String) {
    private var pos = 0

    private fun skipWs() {
        while (pos < input.length && input[pos].isWhitespace()) pos++
    }

    private fun current(): Char = if (pos < input.length) input[pos] else '\u0000'

    private fun readIdent(): String {
        val sb = StringBuilder()
        while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '-' || input[pos] == '_')) {
            sb.append(input[pos++])
        }
        return sb.toString()
    }

    private fun readUntil(vararg chars: Char): String {
        val sb = StringBuilder()
        while (pos < input.length && input[pos] !in chars) {
            sb.append(input[pos++])
        }
        return sb.toString().trim()
    }

    fun parse(): List<StyleRule> {
        val rules = mutableListOf<StyleRule>()
        while (pos < input.length) {
            skipWs()
            if (pos >= input.length) break
            val rule = parseRule() ?: break
            rules.add(rule)
        }
        return rules
    }

    private fun parseRule(): StyleRule? {
        skipWs()
        if (pos >= input.length) return null

        val selector = parseSelector() ?: return null
        skipWs()
        if (current() != '{') return null
        pos++ // {

        val properties = mutableMapOf<String, String>()
        while (pos < input.length && current() != '}') {
            skipWs()
            if (current() == '}') break

            val key = readUntil(':', '}')
            if (current() != ':') break
            pos++ // :
            skipWs()

            val value = readPropertyValue()
            if (key.isNotBlank()) {
                properties[key.trim()] = value.trim()
            }
            skipWs()
            if (current() == ';') pos++
        }
        if (current() == '}') pos++
        return StyleRule(selector, properties)
    }

    private fun parseSelector(): StyleSelector? {
        skipWs()
        return when {
            current() == '*' -> { pos++; StyleSelector.Universal }
            current() == '#' -> {
                pos++
                val id = readIdent()
                if (id.isBlank()) null else StyleSelector.ById(id)
            }
            current() == '.' -> {
                pos++
                val cls = readIdent()
                if (cls.isBlank()) null else StyleSelector.ByClass(cls)
            }
            else -> null
        }
    }

    private fun readPropertyValue(): String {
        skipWs()
        return if (current() == '"') {
            pos++ // opening "
            val sb = StringBuilder()
            while (pos < input.length && current() != '"') {
                sb.append(input[pos++])
            }
            if (current() == '"') pos++
            sb.toString()
        } else {
            readUntil(';', '}', '\n')
        }
    }
}

object Stylesheet {
    fun parse(css: String): List<StyleRule> = StylesheetParser(css).parse()

    /**
     * Apply stylesheet rules to a node.
     * Only sets properties the node doesn't already have explicitly.
     */
    fun applyToNode(node: DotNode, rules: List<StyleRule>) {
        // Sort by specificity descending so higher specificity runs first and wins
        val sorted = rules.sortedByDescending { it.selector.specificity }
        for (rule in sorted) {
            if (rule.selector.matches(node)) {
                for ((prop, value) in rule.properties) {
                    // Only set if not already explicitly set
                    if (!node.attrs.containsKey(prop)) {
                        node.attrs[prop] = DotValue.StringValue(value)
                    }
                }
            }
        }
    }

    fun applyToGraph(graph: DotGraph, css: String) {
        if (css.isBlank()) return
        val rules = parse(css)
        graph.nodes.values.forEach { applyToNode(it, rules) }
    }
}
