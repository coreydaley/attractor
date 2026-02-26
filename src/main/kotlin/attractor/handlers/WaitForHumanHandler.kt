package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.human.Answer
import attractor.human.AnswerValue
import attractor.human.Interviewer
import attractor.human.Option
import attractor.human.Question
import attractor.human.QuestionType
import attractor.state.Context
import attractor.state.Outcome

class WaitForHumanHandler(private val interviewer: Interviewer) : Handler {

    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        // 1. Derive choices from outgoing edges
        val edges = graph.outgoingEdges(node.id)
        data class Choice(val key: String, val label: String, val to: String)

        val choices = edges.map { edge ->
            val label = edge.label.ifEmpty { edge.to }
            val key = parseAcceleratorKey(label)
            Choice(key, label, edge.to)
        }

        if (choices.isEmpty()) {
            return Outcome.fail("No outgoing edges for human gate '${node.id}'")
        }

        // 2. Build question from choices
        val options = choices.map { Option(key = it.key, label = it.label) }
        val question = Question(
            text = node.label.ifEmpty { "Select an option:" },
            type = QuestionType.MULTIPLE_CHOICE,
            options = options,
            stage = node.id
        )

        // 3. Present to interviewer and wait for answer
        val answer = interviewer.ask(question)

        // 4. Handle timeout/skip
        if (answer.isTimeout) {
            val defaultChoice = node.attrOrDefault("human.default_choice", "")
            if (defaultChoice.isBlank()) {
                return Outcome.retry("Human gate timeout, no default configured")
            }
            val target = choices.firstOrNull { it.to == defaultChoice } ?: choices.first()
            return Outcome(
                status = attractor.state.StageStatus.SUCCESS,
                suggestedNextIds = listOf(target.to),
                contextUpdates = mapOf(
                    "human.gate.selected" to target.key,
                    "human.gate.label" to target.label
                ),
                notes = "Human gate timed out, using default: ${target.label}"
            )
        }

        if (answer.isSkipped) {
            return Outcome.fail("Human skipped interaction at gate '${node.id}'")
        }

        // 5. Find matching choice
        val selected = findMatchingChoice(answer, choices.map { Triple(it.key, it.label, it.to) })
            ?: Triple(choices.first().key, choices.first().label, choices.first().to)

        // 6. Record in context and return
        return Outcome(
            status = attractor.state.StageStatus.SUCCESS,
            suggestedNextIds = listOf(selected.third),
            contextUpdates = mapOf(
                "human.gate.selected" to selected.first,
                "human.gate.label" to selected.second
            ),
            notes = "Human selected: ${selected.second}"
        )
    }

    private fun parseAcceleratorKey(label: String): String {
        // [K] Label pattern
        val bracketPattern = Regex("^\\[([A-Za-z0-9])\\]")
        bracketPattern.find(label)?.let { return it.groupValues[1].uppercase() }

        // K) Label pattern
        val parenPattern = Regex("^([A-Za-z0-9])\\)")
        parenPattern.find(label)?.let { return it.groupValues[1].uppercase() }

        // K - Label pattern
        val dashPattern = Regex("^([A-Za-z0-9]) -")
        dashPattern.find(label)?.let { return it.groupValues[1].uppercase() }

        // First character
        return if (label.isNotEmpty()) label.first().uppercase() else "?"
    }

    private fun normalizeLabel(label: String): String {
        return label
            .lowercase()
            .trim()
            .replace(Regex("^\\[[a-z0-9]\\] "), "")
            .replace(Regex("^[a-z0-9]\\) "), "")
            .replace(Regex("^[a-z0-9] - "), "")
    }

    private fun findMatchingChoice(
        answer: Answer,
        choices: List<Triple<String, String, String>>
    ): Triple<String, String, String>? {
        val answerStr = answer.valueString

        // Match by key
        choices.firstOrNull { it.first.lowercase() == answerStr.lowercase() }?.let { return it }

        // Match by label (normalized)
        val normalizedAnswer = normalizeLabel(answerStr)
        choices.firstOrNull { normalizeLabel(it.second) == normalizedAnswer }?.let { return it }

        // Match by node ID
        choices.firstOrNull { it.third == answerStr }?.let { return it }

        return null
    }
}
