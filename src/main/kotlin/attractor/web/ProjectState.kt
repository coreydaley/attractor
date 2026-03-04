package attractor.web

import attractor.events.ProjectEvent
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class StageRecord(
    val index: Int,
    val name: String,
    val nodeId: String = "",  // node.id — used to locate log files on disk
    val status: String,       // "running" | "completed" | "failed" | "retrying" | "diagnosing" | "repairing"
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val hasLog: Boolean = false
)

class ProjectState {
    val projectName  = AtomicReference<String>("")
    val runId        = AtomicReference<String>("")
    val currentNode  = AtomicReference<String>("")
    val status       = AtomicReference<String>("idle") // idle | running | completed | failed | cancelled
    val startedAt    = AtomicReference<Long>(0L)
    val finishedAt   = AtomicReference<Long>(0L)
    val stages       = CopyOnWriteArrayList<StageRecord>()
    val recentLogs   = ConcurrentLinkedDeque<String>()
    internal val recentLogsSize = AtomicInteger(0)
    val cancelToken       = java.util.concurrent.atomic.AtomicBoolean(false)
    val pauseToken        = java.util.concurrent.atomic.AtomicBoolean(false)
    val archived          = java.util.concurrent.atomic.AtomicBoolean(false)
    val hasFailureReport  = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile var logsRoot: String = ""

    private fun checkHasLog(nodeId: String): Boolean {
        val lr = logsRoot
        return lr.isNotBlank() && nodeId.isNotBlank() &&
            java.io.File(lr, "$nodeId/live.log").let { it.exists() && it.length() > 0 }
    }

    fun update(event: ProjectEvent) {
        when (event) {
            is ProjectEvent.ProjectStarted -> {
                projectName.set(event.name)
                runId.set(event.id)
                status.set("running")
                startedAt.set(System.currentTimeMillis())
                finishedAt.set(0L)
                stages.clear()
                recentLogs.clear()
                recentLogsSize.set(0)
                log("Project started: ${event.name} [${event.id}]")
            }
            is ProjectEvent.StageStarted -> {
                currentNode.set(event.name)
                stages.add(StageRecord(event.index, event.name, event.nodeId, "running", startedAt = System.currentTimeMillis()))
                log("[${event.index}] ▶ ${event.name}")
            }
            is ProjectEvent.StageCompleted -> {
                updateStage(event.name, "running") { it.copy(status = "completed", durationMs = event.durationMs, hasLog = checkHasLog(it.nodeId)) }
                log("[${event.index}] ✓ ${event.name} (${event.durationMs}ms)")
            }
            is ProjectEvent.StageFailed -> {
                // Stage may be "running" or "retrying" when the final failure fires
                val now = System.currentTimeMillis()
                val updated = updateStageAny(event.name) {
                    val dur = if (it.startedAt != null) now - it.startedAt else null
                    it.copy(status = "failed", durationMs = dur, error = event.error, hasLog = checkHasLog(it.nodeId))
                }
                if (!updated) stages.add(StageRecord(event.index, event.name, status = "failed", error = event.error))
                log("[${event.index}] ✗ ${event.name}: ${event.error}")
            }
            is ProjectEvent.StageRetrying -> {
                updateStage(event.name, "running") { it.copy(status = "retrying") }
                log("[${event.index}] ↻ ${event.name} retry ${event.attempt} (delay ${event.delayMs}ms)")
            }
            is ProjectEvent.ProjectCompleted -> {
                status.set("completed")
                finishedAt.set(System.currentTimeMillis())
                currentNode.set("")
                log("Project completed in ${event.durationMs}ms ✓")
            }
            is ProjectEvent.ProjectFailed -> {
                status.set("failed")
                finishedAt.set(System.currentTimeMillis())
                log("Project FAILED: ${event.error}")
            }
            is ProjectEvent.ProjectCancelled -> {
                status.set("cancelled")
                finishedAt.set(System.currentTimeMillis())
                currentNode.set("")
                log("Project cancelled after ${event.durationMs}ms")
            }
            is ProjectEvent.ProjectPaused -> {
                status.set("paused")
                finishedAt.set(System.currentTimeMillis())
                currentNode.set("")
                log("Project paused after ${event.durationMs}ms")
            }
            is ProjectEvent.CheckpointSaved ->
                log("Checkpoint → ${event.nodeId}")
            is ProjectEvent.DiagnosticsStarted -> {
                val idx = stages.indexOfLast { it.nodeId == event.nodeId }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "diagnosing")
                log("[${event.stageIndex}] \uD83D\uDD0D Diagnosing failure: ${event.stageName}")
            }
            is ProjectEvent.DiagnosticsCompleted -> {
                val fixable = if (event.recoverable) "fixable" else "unrecoverable"
                log("[${event.stageIndex}] \uD83D\uDCCB Diagnosis: $fixable — ${event.strategy}: ${event.explanation.take(120)}")
            }
            is ProjectEvent.RepairAttempted -> {
                val idx = stages.indexOfLast { it.name == event.stageName }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "repairing")
                log("[${event.stageIndex}] \uD83D\uDD27 Repair attempt: ${event.stageName}")
            }
            is ProjectEvent.RepairSucceeded -> {
                val idx = stages.indexOfLast { it.name == event.stageName }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "completed", durationMs = event.durationMs)
                log("[${event.stageIndex}] ✓ Repair succeeded: ${event.stageName} (${event.durationMs}ms)")
            }
            is ProjectEvent.RepairFailed -> {
                val idx = stages.indexOfLast { it.name == event.stageName }
                if (idx >= 0) stages[idx] = stages[idx].copy(status = "failed", error = event.reason, hasLog = checkHasLog(stages[idx].nodeId))
                log("[${event.stageIndex}] ✗ Repair failed: ${event.stageName}: ${event.reason}")
            }
            is ProjectEvent.InterviewStarted ->
                log("Human gate: ${event.questionText}")
            is ProjectEvent.InterviewCompleted ->
                log("Answer received: ${event.answer}")
            is ProjectEvent.ParallelStarted ->
                log("Parallel: ${event.branchCount} branches starting")
            is ProjectEvent.ParallelCompleted ->
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
        projectName.set("")
        runId.set("")
        currentNode.set("")
        status.set("idle")
        startedAt.set(0L)
        finishedAt.set(0L)
        stages.clear()
        recentLogs.clear()
        recentLogsSize.set(0)
        cancelToken.set(false)
        pauseToken.set(false)
        archived.set(false)
        hasFailureReport.set(false)
    }

    private fun log(msg: String) {
        recentLogs.addLast("[${Instant.now()}] $msg")
        if (recentLogsSize.incrementAndGet() > 200) {
            recentLogs.pollFirst()
            recentLogsSize.decrementAndGet()
        }
    }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"project\":${js(projectName.get())},")
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
            sb.append("{\"index\":${s.index},\"name\":${js(s.name)},\"nodeId\":${js(s.nodeId)},\"status\":${js(s.status)},\"hasLog\":${s.hasLog}")
            if (s.startedAt != null) sb.append(",\"startedAt\":${s.startedAt}")
            if (s.durationMs != null) sb.append(",\"durationMs\":${s.durationMs}")
            if (s.error != null) sb.append(",\"error\":${js(s.error)}")
            sb.append("}")
        }
        sb.append("],\"logs\":[")
        val logList = recentLogs.toList()
        val logsToShow = if (logList.size > 50) logList.subList(logList.size - 50, logList.size) else logList
        logsToShow.forEachIndexed { i, l ->
            if (i > 0) sb.append(",")
            sb.append(js(l))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun js(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")}\""
}
