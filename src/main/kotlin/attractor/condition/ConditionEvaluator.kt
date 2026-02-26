package attractor.condition

import attractor.state.Context
import attractor.state.Outcome

/**
 * Evaluates edge condition expressions per Section 10 of the spec.
 *
 * Grammar:
 *   ConditionExpr ::= Clause ( '&&' Clause )*
 *   Clause        ::= Key Operator Literal
 *   Key           ::= 'outcome' | 'preferred_label' | 'context.' Path
 *   Operator      ::= '=' | '!='
 *   Literal       ::= String | Integer | Boolean
 */
object ConditionEvaluator {

    fun evaluate(condition: String, outcome: Outcome, context: Context): Boolean {
        if (condition.isBlank()) return true

        val clauses = splitClauses(condition)
        return clauses.all { evaluateClause(it.trim(), outcome, context) }
    }

    private fun splitClauses(condition: String): List<String> {
        // Split on && but not inside quoted strings
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < condition.length) {
            if (i + 1 < condition.length && condition[i] == '&' && condition[i + 1] == '&') {
                result.add(current.toString())
                current.clear()
                i += 2
            } else {
                current.append(condition[i])
                i++
            }
        }
        result.add(current.toString())
        return result.filter { it.isNotBlank() }
    }

    private fun evaluateClause(clause: String, outcome: Outcome, context: Context): Boolean {
        return when {
            clause.contains("!=") -> {
                val idx = clause.indexOf("!=")
                val key = clause.substring(0, idx).trim()
                val value = clause.substring(idx + 2).trim().unquote()
                resolveKey(key, outcome, context) != value
            }
            clause.contains("=") -> {
                val idx = clause.indexOf("=")
                val key = clause.substring(0, idx).trim()
                val value = clause.substring(idx + 1).trim().unquote()
                resolveKey(key, outcome, context) == value
            }
            else -> {
                // Bare key: truthy check
                val resolved = resolveKey(clause.trim(), outcome, context)
                resolved.isNotEmpty() && resolved != "false" && resolved != "0"
            }
        }
    }

    private fun String.unquote(): String {
        if (length >= 2 && startsWith('"') && endsWith('"')) {
            return substring(1, length - 1)
        }
        return this
    }

    fun resolveKey(key: String, outcome: Outcome, context: Context): String {
        return when {
            key == "outcome" -> outcome.status.toString().lowercase()
            key == "preferred_label" -> outcome.preferredLabel
            key.startsWith("context.") -> {
                val contextKey = key.removePrefix("context.")
                val direct = context.getString(key)
                if (direct.isNotEmpty()) direct
                else context.getString(contextKey)
            }
            else -> {
                // Try direct context lookup
                val v = context.getString(key)
                v
            }
        }
    }
}
