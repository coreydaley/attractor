package attractor.web

import attractor.events.PipelineEvent
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

data class StageRecord(
    val index: Int,
    val name: String,
    val nodeId: String = "",  // node.id — used to locate log files on disk
    val status: String,       // "running" | "completed" | "failed" | "retrying" | "diagnosing" | "repairing"
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null
)

class PipelineState {
    val pipelineName = AtomicReference<String>("")
    val runId        = AtomicReference<String>("")
    val currentNode  = AtomicReference<String>("")
    val status       = AtomicReference<String>("idle") // idle | running | completed | failed | cancelled
    val startedAt    = AtomicReference<Long>(0L)
    val finishedAt   = AtomicReference<Long>(0L)
    val stages       = CopyOnWriteArrayList<StageRecord>()
    val recentLogs   = CopyOnWriteArrayList<String>()
    val cancelToken       = java.util.concurrent.atomic.AtomicBoolean(false)
    val pauseToken        = java.util.concurrent.atomic.AtomicBoolean(false)
    val archived          = java.util.concurrent.atomic.AtomicBoolean(false)
    val hasFailureReport  = java.util.concurrent.atomic.AtomicBoolean(false)

    fun update(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.PipelineStarted -> {
                pipelineName.set(event.name)
                runId.set(event.id)
                status.set("running")
                startedAt.set(System.currentTimeMillis())
                finishedAt.set(0L)
                stages.clear()
                recentLogs.clear()
                log("Pipeline started: ${event.name} [${event.id}]")
            }
            is PipelineEvent.StageStarted -> {
                currentNode.set(event.name)
                stages.add(StageRecord(event.index, event.name, event.nodeId, "running", startedAt = System.currentTimeMillis()))
                log("[${event.index}] ▶ ${event.name}")
            }
            is PipelineEvent.StageCompleted -> {
                updateStage(event.name, "running") { it.copy(status = "completed", durationMs = event.durationMs) }
                log("[${event.index}] ✓ ${event.name} (${event.durationMs}ms)")
            }
            is PipelineEvent.StageFailed -> {
                // Stage may be "running" or "retrying" when the final failure fires
                val updated = updateStageAny(event.name) { it.copy(status = "failed", error = event.error) }
                if (!updated) stages.add(StageRecord(event.index, event.name, status = "failed", error = event.error))
                log("[${event.index}] ✗ ${event.name}: ${event.error}")
            }
            is PipelineEvent.StageRetrying -> {
                updateStage(event.name, "running") { it.copy(status = "retrying") }
                log("[${event.index}] ↻ ${event.name} retry ${event.attempt} (delay ${event.delayMs}ms)")
            }
            is PipelineEvent.PipelineCompleted -> {
                status.set("completed")
                finishedAt.set(System.currentTimeMillis())
                currentNode.set("")
                log("Pipeline completed in ${event.durationMs}ms ✓")
            }
            is PipelineEvent.PipelineFailed -> {
                status.set("failed")
                finishedAt.set(System.currentTimeMillis())
                log("Pipeline FAILED: ${event.error}")
            }
            is PipelineEvent.PipelineCancelled -> {
                status.set("cancelled")
                finishedAt.set(System.currentTimeMillis())
                currentNode.set("")
                log("Pipeline cancelled after ${event.durationMs}ms")
            }
            is PipelineEvent.PipelinePaused -> {
                status.set("paused")
                finishedAt.set(System.currentTimeMillis())
                currentNode.set("")
                log("Pipeline paused after ${event.durationMs}ms")
            }
            is PipelineEvent.CheckpointSaved ->
                log("Checkpoint → ${event.nodeId}")
            is PipelineEvent.DiagnosticsStarted -> {
                val idx = stages.indexOfLast { it.nodeId == event.nodeId }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "diagnosing")
                log("[${event.stageIndex}] \uD83D\uDD0D Diagnosing failure: ${event.stageName}")
            }
            is PipelineEvent.DiagnosticsCompleted -> {
                val fixable = if (event.recoverable) "fixable" else "unrecoverable"
                log("[${event.stageIndex}] \uD83D\uDCCB Diagnosis: $fixable — ${event.strategy}: ${event.explanation.take(120)}")
            }
            is PipelineEvent.RepairAttempted -> {
                val idx = stages.indexOfLast { it.name == event.stageName }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "repairing")
                log("[${event.stageIndex}] \uD83D\uDD27 Repair attempt: ${event.stageName}")
            }
            is PipelineEvent.RepairSucceeded -> {
                val idx = stages.indexOfLast { it.name == event.stageName }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "completed", durationMs = event.durationMs)
                log("[${event.stageIndex}] ✓ Repair succeeded: ${event.stageName} (${event.durationMs}ms)")
            }
            is PipelineEvent.RepairFailed -> {
                val idx = stages.indexOfLast { it.name == event.stageName }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "failed", error = event.reason)
                log("[${event.stageIndex}] ✗ Repair failed: ${event.stageName}: ${event.reason}")
            }
            is PipelineEvent.InterviewStarted ->
                log("Human gate: ${event.questionText}")
            is PipelineEvent.InterviewCompleted ->
                log("Answer received: ${event.answer}")
            is PipelineEvent.ParallelStarted ->
                log("Parallel: ${event.branchCount} branches starting")
            is PipelineEvent.ParallelCompleted ->
                log("Parallel done: ${event.successCount} ok, ${event.failureCount} failed")
            else -> {}
        }
    }

    private fun updateStage(name: String, fromStatus: String, transform: (StageRecord) -> StageRecord) {
        val idx = stages.indexOfLast { it.name == name && it.status == fromStatus }
        if (idx >= 0) stages[idx] = transform(stages[idx])
    }

    /** Update the last stage with the given name regardless of its current status. Returns true if found. */
    private fun updateStageAny(name: String, transform: (StageRecord) -> StageRecord): Boolean {
        val idx = stages.indexOfLast { it.name == name }
        if (idx >= 0) { stages[idx] = transform(stages[idx]); return true }
        return false
    }

    fun reset() {
        pipelineName.set("")
        runId.set("")
        currentNode.set("")
        status.set("idle")
        startedAt.set(0L)
        finishedAt.set(0L)
        stages.clear()
        recentLogs.clear()
        cancelToken.set(false)
        pauseToken.set(false)
        archived.set(false)
        hasFailureReport.set(false)
    }

    private fun log(msg: String) {
        val entry = "[${Instant.now()}] $msg"
        recentLogs.add(entry)
        while (recentLogs.size > 200) recentLogs.removeAt(0)
    }

    fun toJson(logsRoot: String = ""): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"pipeline\":${js(pipelineName.get())},")
        sb.append("\"runId\":${js(runId.get())},")
        sb.append("\"currentNode\":${js(currentNode.get())},")
        sb.append("\"status\":${js(status.get())},")
        sb.append("\"archived\":${archived.get()},")
        sb.append("\"hasFailureReport\":${hasFailureReport.get()},")
        sb.append("\"startedAt\":${startedAt.get()},")
        sb.append("\"finishedAt\":${finishedAt.get()},")
        sb.append("\"stages\":[")
        stages.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            val hasLog = logsRoot.isNotBlank() && s.nodeId.isNotBlank() &&
                java.io.File(logsRoot, "${s.nodeId}/live.log").let { it.exists() && it.length() > 0 }
            sb.append("{\"index\":${s.index},\"name\":${js(s.name)},\"nodeId\":${js(s.nodeId)},\"status\":${js(s.status)},\"hasLog\":$hasLog")
            if (s.startedAt != null) sb.append(",\"startedAt\":${s.startedAt}")
            if (s.durationMs != null) sb.append(",\"durationMs\":${s.durationMs}")
            if (s.error != null) sb.append(",\"error\":${js(s.error)}")
            sb.append("}")
        }
        sb.append("],\"logs\":[")
        recentLogs.takeLast(50).forEachIndexed { i, l ->
            if (i > 0) sb.append(",")
            sb.append(js(l))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun js(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
}
