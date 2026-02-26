package attractor.handlers

import attractor.dot.DotGraph
import attractor.dot.DotNode
import attractor.state.Context
import attractor.state.Outcome
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Backend interface for LLM execution (Section 4.5).
 * workspaceDir is the shared directory for file I/O across all stages in a run.
 * stageDir is the per-stage log directory (used for live.log, prompt.md, response.md).
 */
interface CodergenBackend {
    fun run(node: DotNode, prompt: String, context: Context, workspaceDir: File, stageDir: File): Any // String or Outcome
}

/**
 * Simulation backend - returns a canned response for testing.
 */
object SimulationBackend : CodergenBackend {
    override fun run(node: DotNode, prompt: String, context: Context, workspaceDir: File, stageDir: File): Any =
        "[Simulated] Response for stage: ${node.id}"
}

class CodergenHandler(val backend: CodergenBackend? = null) : Handler {
    private val json = Json { prettyPrint = true }

    override fun execute(node: DotNode, context: Context, graph: DotGraph, logsRoot: String): Outcome {
        // 1. Set up directories
        val stageDir = File(logsRoot, node.id)
        stageDir.mkdirs()
        // Shared workspace persists across all stages in a pipeline run
        val workspaceDir = File(logsRoot, "workspace")
        workspaceDir.mkdirs()

        // 2. Build prompt with variable expansion
        var prompt = node.prompt.ifEmpty { node.label }
        prompt = expandVariables(prompt, graph, context, workspaceDir)

        // 3. Write prompt to logs
        File(stageDir, "prompt.md").writeText(prompt)

        // 4. Call LLM backend
        val responseText: String
        if (backend != null) {
            try {
                val result = backend.run(node, prompt, context, workspaceDir, stageDir)
                if (result is Outcome) {
                    writeStatus(stageDir, result)
                    return result
                }
                responseText = result.toString()
            } catch (e: Exception) {
                val outcome = Outcome.fail(e.message ?: "Backend error")
                writeStatus(stageDir, outcome)
                return outcome
            }
        } else {
            responseText = "[Simulated] Response for stage: ${node.id}"
        }

        // 5. Write response to logs
        File(stageDir, "response.md").writeText(responseText)

        // 6. Write status and return outcome
        val outcome = Outcome(
            status = attractor.state.StageStatus.SUCCESS,
            notes = "Stage completed: ${node.id}",
            contextUpdates = mapOf(
                "last_stage" to node.id,
                "last_response" to responseText.take(4000),
                "workspace.dir" to workspaceDir.absolutePath
            )
        )
        writeStatus(stageDir, outcome)
        return outcome
    }

    private fun writeStatus(stageDir: File, outcome: Outcome) {
        val statusData = mapOf(
            "status" to outcome.status.toString(),
            "notes" to outcome.notes,
            "failure_reason" to outcome.failureReason,
            "preferred_label" to outcome.preferredLabel
        )
        File(stageDir, "status.json").writeText(json.encodeToString(statusData))
    }

    private fun expandVariables(prompt: String, graph: DotGraph, context: Context, workspaceDir: File): String {
        var result = prompt
        // Built-in vars
        result = result.replace("\$goal", graph.goal)
        result = result.replace("\$workspace", workspaceDir.absolutePath)
        // Expand from context (covers last_response, tool.output, graph.goal, etc.)
        for ((key, value) in context.snapshot()) {
            result = result.replace("\$$key", value.toString())
            result = result.replace("\${$key}", value.toString())
        }
        return result
    }
}
