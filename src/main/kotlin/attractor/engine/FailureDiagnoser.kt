package attractor.engine

import attractor.handlers.LlmCodergenBackend
import attractor.llm.Client
import attractor.llm.generate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// ─── Data Types ───────────────────────────────────────────────────────────────

enum class FixStrategy { RETRY_WITH_HINT, SKIP, ABORT }

data class FailureContext(
    val nodeId: String,
    val stageName: String,
    val stageIndex: Int,
    val failureReason: String,
    val logsRoot: String,
    val contextSnapshot: Map<String, Any>
)

@Serializable
data class DiagnosisResult(
    val recoverable: Boolean,
    val strategy: String,       // FixStrategy name
    val explanation: String,
    val repairHint: String? = null
)

// ─── Interface ────────────────────────────────────────────────────────────────

fun interface FailureDiagnoser {
    fun analyze(ctx: FailureContext): DiagnosisResult
}

// ─── Null (no-op) implementation ──────────────────────────────────────────────

class NullFailureDiagnoser(private val reason: String = "no LLM diagnoser configured") : FailureDiagnoser {
    override fun analyze(ctx: FailureContext) = DiagnosisResult(
        recoverable = false,
        strategy = "ABORT",
        explanation = reason
    )
}

// ─── LLM-powered implementation ───────────────────────────────────────────────

class LlmFailureDiagnoser(
    private val client: Client,
    private val model: String = LlmCodergenBackend.DEFAULT_MODEL
) : FailureDiagnoser {

    companion object {
        private const val MAX_FAILURE_REASON_LEN = 1000
        private const val MAX_ARTIFACT_LEN = 500
        private const val MAX_LOG_LINES = 30

        private val SYSTEM_PROMPT = """
You are a pipeline failure analyst. Given information about a failed pipeline stage,
classify the failure and decide if an automatic repair is possible.

Respond ONLY with a valid JSON object with these exact fields:
{
  "recoverable": true or false,
  "strategy": "RETRY_WITH_HINT" or "SKIP" or "ABORT",
  "explanation": "concise explanation of what went wrong and your decision",
  "repairHint": "actionable hint for repair (max 300 chars)" or null
}

RETRY_WITH_HINT: the failure is transient or fixable with better guidance.
  The repairHint will be injected as context variable 'repair.hint' for the retry.
SKIP: the stage can be safely skipped (treat as partial success).
  Only use this when instructed the stage is non-critical.
ABORT: the failure is deterministic or unrecoverable. Set repairHint to null.
""".trimIndent()
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun analyze(ctx: FailureContext): DiagnosisResult {
        return try {
            val userPrompt = buildUserPrompt(ctx)
            val result = generate(
                model = model,
                prompt = userPrompt,
                system = SYSTEM_PROMPT,
                maxTokens = 512,
                client = client
            )
            parseResponse(result.text.trim())
        } catch (e: Exception) {
            DiagnosisResult(
                recoverable = false,
                strategy = "ABORT",
                explanation = "diagnoser exception: ${e.message?.take(200)}"
            )
        }
    }

    private fun buildUserPrompt(ctx: FailureContext): String {
        val sb = StringBuilder()
        sb.appendLine("## Failed Stage")
        sb.appendLine("Node ID: ${ctx.nodeId}")
        sb.appendLine("Stage Name: ${ctx.stageName}")
        sb.appendLine("Stage Index: ${ctx.stageIndex}")
        sb.appendLine()

        sb.appendLine("## Failure Reason")
        sb.appendLine(ctx.failureReason.take(MAX_FAILURE_REASON_LEN))
        sb.appendLine()

        val stageDir = File(ctx.logsRoot, ctx.nodeId)

        val promptFile = File(stageDir, "prompt.md")
        if (promptFile.exists()) {
            val content = promptFile.readText()
            val excerpt = content.takeLast(MAX_ARTIFACT_LEN)
            sb.appendLine("## Stage Prompt (last $MAX_ARTIFACT_LEN chars)")
            sb.appendLine(excerpt)
            sb.appendLine()
        }

        val responseFile = File(stageDir, "response.md")
        if (responseFile.exists()) {
            val content = responseFile.readText()
            val excerpt = content.takeLast(MAX_ARTIFACT_LEN)
            sb.appendLine("## Stage Response (last $MAX_ARTIFACT_LEN chars)")
            sb.appendLine(excerpt)
            sb.appendLine()
        }

        val statusFile = File(stageDir, "status.json")
        if (statusFile.exists()) {
            sb.appendLine("## Stage Status")
            sb.appendLine(statusFile.readText())
            sb.appendLine()
        }

        val logFile = File(stageDir, "live.log")
        if (logFile.exists()) {
            val lines = logFile.readLines().takeLast(MAX_LOG_LINES)
            if (lines.isNotEmpty()) {
                sb.appendLine("## Stage Log (last $MAX_LOG_LINES lines)")
                sb.appendLine(lines.joinToString("\n"))
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    private fun parseResponse(text: String): DiagnosisResult {
        return try {
            json.decodeFromString<DiagnosisResult>(text)
        } catch (e: Exception) {
            // Try to extract JSON from text if LLM added preamble
            val jsonStart = text.indexOf('{')
            val jsonEnd = text.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                try {
                    json.decodeFromString<DiagnosisResult>(text.substring(jsonStart, jsonEnd + 1))
                } catch (e2: Exception) {
                    DiagnosisResult(
                        recoverable = false,
                        strategy = "ABORT",
                        explanation = "diagnosis parse error: ${e.message?.take(200)}"
                    )
                }
            } else {
                DiagnosisResult(
                    recoverable = false,
                    strategy = "ABORT",
                    explanation = "diagnosis parse error: no JSON found in response"
                )
            }
        }
    }
}
