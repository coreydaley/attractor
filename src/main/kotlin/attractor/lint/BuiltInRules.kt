package attractor.lint

import attractor.condition.ConditionEvaluator
import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.style.StylesheetParser

private val VALID_FIDELITY = setOf("full", "truncate", "compact", "summary:low", "summary:medium", "summary:high")
private val KNOWN_TYPES = setOf(
    "start", "exit", "codergen", "wait.human", "conditional",
    "parallel", "parallel.fan_in", "tool", "stack.manager_loop"
)

object BuiltInRules {
    val ALL: List<LintRule> = listOf(
        StartNodeRule,
        TerminalNodeRule,
        ReachabilityRule,
        EdgeTargetExistsRule,
        StartNoIncomingRule,
        ExitNoOutgoingRule,
        ConditionSyntaxRule,
        StylesheetSyntaxRule,
        TypeKnownRule,
        FidelityValidRule,
        RetryTargetExistsRule,
        GoalGateHasRetryRule,
        PromptOnLlmNodesRule
    )
}

object StartNodeRule : LintRule {
    override val name = "start_node"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val starts = graph.nodes.values.filter { it.isStart() }
        return when {
            starts.isEmpty() -> listOf(
                Diagnostic(name, Severity.ERROR,
                    "Project must have exactly one start node (shape=Mdiamond or id=start)",
                    fix = "Add a node with shape=Mdiamond or id=start")
            )
            starts.size > 1 -> listOf(
                Diagnostic(name, Severity.ERROR,
                    "Project has ${starts.size} start nodes; must have exactly one: ${starts.map { it.id }}",
                    fix = "Remove extra start nodes")
            )
            else -> emptyList()
        }
    }
}

object TerminalNodeRule : LintRule {
    override val name = "terminal_node"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val exits = graph.nodes.values.filter { it.isExit() }
        return if (exits.isEmpty()) {
            listOf(Diagnostic(name, Severity.ERROR,
                "Project must have at least one exit node (shape=Msquare or id=exit/end)",
                fix = "Add a node with shape=Msquare or id=exit"))
        } else emptyList()
    }
}

object ReachabilityRule : LintRule {
    override val name = "reachability"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val start = graph.startNode() ?: return emptyList()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(start.id)
        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            if (!visited.add(nodeId)) continue
            graph.outgoingEdges(nodeId).forEach { edge ->
                if (edge.to !in visited) queue.add(edge.to)
            }
        }
        val unreachable = graph.nodes.keys.filter { it !in visited }
        return unreachable.map { id ->
            Diagnostic(name, Severity.ERROR,
                "Node '$id' is unreachable from the start node",
                nodeId = id,
                fix = "Add an edge from a reachable node to '$id' or remove it")
        }
    }
}

object EdgeTargetExistsRule : LintRule {
    override val name = "edge_target_exists"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        return graph.edges.flatMap { edge ->
            val errors = mutableListOf<Diagnostic>()
            if (edge.from !in graph.nodes) {
                errors.add(Diagnostic(name, Severity.ERROR,
                    "Edge references unknown source node '${edge.from}'",
                    edge = Pair(edge.from, edge.to)))
            }
            if (edge.to !in graph.nodes) {
                errors.add(Diagnostic(name, Severity.ERROR,
                    "Edge references unknown target node '${edge.to}'",
                    edge = Pair(edge.from, edge.to)))
            }
            errors
        }
    }
}

object StartNoIncomingRule : LintRule {
    override val name = "start_no_incoming"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val start = graph.startNode() ?: return emptyList()
        val incoming = graph.incomingEdges(start.id)
        return if (incoming.isNotEmpty()) {
            listOf(Diagnostic(name, Severity.ERROR,
                "Start node '${start.id}' must have no incoming edges",
                nodeId = start.id,
                fix = "Remove edges pointing to the start node"))
        } else emptyList()
    }
}

object ExitNoOutgoingRule : LintRule {
    override val name = "exit_no_outgoing"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val exits = graph.nodes.values.filter { it.isExit() }
        return exits.flatMap { exit ->
            val outgoing = graph.outgoingEdges(exit.id)
            if (outgoing.isNotEmpty()) {
                listOf(Diagnostic(name, Severity.ERROR,
                    "Exit node '${exit.id}' must have no outgoing edges",
                    nodeId = exit.id,
                    fix = "Remove edges from the exit node"))
            } else emptyList()
        }
    }
}

object ConditionSyntaxRule : LintRule {
    override val name = "condition_syntax"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        return graph.edges.flatMap { edge ->
            val cond = edge.condition
            if (cond.isBlank()) return@flatMap emptyList()
            val errors = mutableListOf<Diagnostic>()
            // Basic validation: split on && and check each clause has an operator
            val clauses = cond.split("&&").map { it.trim() }.filter { it.isNotBlank() }
            for (clause in clauses) {
                if (!clause.contains("=") && !clause.contains("!=")) {
                    // Bare key - check it's a valid identifier-like key
                    if (!clause.matches(Regex("[a-zA-Z0-9_.]+( *(=|!=) *.*)?"))) {
                        errors.add(Diagnostic(name, Severity.ERROR,
                            "Invalid condition clause: '$clause' in edge ${edge.from}->${edge.to}",
                            edge = Pair(edge.from, edge.to)))
                    }
                }
            }
            errors
        }
    }
}

object StylesheetSyntaxRule : LintRule {
    override val name = "stylesheet_syntax"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val css = graph.modelStylesheet
        if (css.isBlank()) return emptyList()
        return try {
            StylesheetParser(css).parse()
            emptyList()
        } catch (e: Exception) {
            listOf(Diagnostic(name, Severity.ERROR,
                "model_stylesheet parse error: ${e.message}",
                fix = "Fix the stylesheet syntax"))
        }
    }
}

object TypeKnownRule : LintRule {
    override val name = "type_known"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        return graph.nodes.values.mapNotNull { node ->
            val type = node.type
            if (type.isNotBlank() && type !in KNOWN_TYPES) {
                Diagnostic(name, Severity.WARNING,
                    "Node '${node.id}' has unknown type '$type'",
                    nodeId = node.id,
                    fix = "Use one of: ${KNOWN_TYPES.joinToString()}")
            } else null
        }
    }
}

object FidelityValidRule : LintRule {
    override val name = "fidelity_valid"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        return graph.nodes.values.mapNotNull { node ->
            val fidelity = node.fidelity
            if (fidelity.isNotBlank() && fidelity !in VALID_FIDELITY) {
                Diagnostic(name, Severity.WARNING,
                    "Node '${node.id}' has invalid fidelity '$fidelity'",
                    nodeId = node.id,
                    fix = "Use one of: ${VALID_FIDELITY.joinToString()}")
            } else null
        }
    }
}

object RetryTargetExistsRule : LintRule {
    override val name = "retry_target_exists"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()
        graph.nodes.values.forEach { node ->
            listOf(node.retryTarget, node.fallbackRetryTarget).forEach { target ->
                if (target.isNotBlank() && target !in graph.nodes) {
                    diags.add(Diagnostic(name, Severity.WARNING,
                        "Node '${node.id}' retry_target '$target' does not exist",
                        nodeId = node.id))
                }
            }
        }
        listOf(graph.retryTarget, graph.fallbackRetryTarget).forEach { target ->
            if (target.isNotBlank() && target !in graph.nodes) {
                diags.add(Diagnostic(name, Severity.WARNING,
                    "Graph-level retry_target '$target' does not exist"))
            }
        }
        return diags
    }
}

object GoalGateHasRetryRule : LintRule {
    override val name = "goal_gate_has_retry"
    override fun apply(graph: DotGraph): List<Diagnostic> {
        return graph.nodes.values.mapNotNull { node ->
            if (node.goalGate) {
                val hasRetry = node.retryTarget.isNotBlank() ||
                        node.fallbackRetryTarget.isNotBlank() ||
                        graph.retryTarget.isNotBlank() ||
                        graph.fallbackRetryTarget.isNotBlank()
                if (!hasRetry) {
                    Diagnostic(name, Severity.WARNING,
                        "Goal gate node '${node.id}' has no retry_target; project may fail if gate is unsatisfied",
                        nodeId = node.id,
                        fix = "Add a retry_target or fallback_retry_target on the node or graph")
                } else null
            } else null
        }
    }
}

object PromptOnLlmNodesRule : LintRule {
    override val name = "prompt_on_llm_nodes"
    private val NON_LLM_SHAPES = setOf("Mdiamond", "Msquare", "hexagon", "diamond", "component",
        "tripleoctagon", "house")
    private val NON_LLM_TYPES = setOf("start", "exit", "wait.human", "conditional",
        "parallel", "parallel.fan_in", "stack.manager_loop")

    override fun apply(graph: DotGraph): List<Diagnostic> {
        return graph.nodes.values.mapNotNull { node ->
            val isLlm = node.shape !in NON_LLM_SHAPES && node.type !in NON_LLM_TYPES
            if (isLlm && node.prompt.isBlank() && node.label.isBlank()) {
                Diagnostic(name, Severity.WARNING,
                    "LLM node '${node.id}' has no prompt or label",
                    nodeId = node.id,
                    fix = "Add a prompt or label attribute")
            } else null
        }
    }
}
