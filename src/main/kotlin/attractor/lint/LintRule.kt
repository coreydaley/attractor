package attractor.lint

import attractor.dot.DotGraph

interface LintRule {
    val name: String
    fun apply(graph: DotGraph): List<Diagnostic>
}
