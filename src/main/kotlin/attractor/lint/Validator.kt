package attractor.lint

import attractor.dot.DotGraph

object Validator {
    fun validate(graph: DotGraph, extraRules: List<LintRule> = emptyList()): List<Diagnostic> {
        val rules = BuiltInRules.ALL + extraRules
        return rules.flatMap { it.apply(graph) }
    }

    fun validateOrRaise(graph: DotGraph, extraRules: List<LintRule> = emptyList()): List<Diagnostic> {
        val diagnostics = validate(graph, extraRules)
        val errors = diagnostics.filter { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) {
            val msg = "Project validation failed with ${errors.size} error(s):\n" +
                    errors.joinToString("\n") { "  [${it.rule}] ${it.message}" }
            throw ValidationException(msg, errors)
        }
        return diagnostics
    }
}
