package attractor.web

import attractor.db.RunStore
import attractor.dot.Parser
import attractor.state.Checkpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

data class PipelineEntry(
    val id: String,
    val fileName: String,
    val state: PipelineState,
    val dotSource: String = "",
    val options: RunOptions = RunOptions(),
    val logsRoot: String = "",
    val originalPrompt: String = "",
    val displayName: String = "",
    val familyId: String = "",
    var isHydratedViewOnly: Boolean = false
)

class PipelineRegistry(private val store: RunStore) {
    private val entries = ConcurrentHashMap<String, PipelineEntry>()
    private val orderedIds = CopyOnWriteArrayList<String>()
    private val futures = ConcurrentHashMap<String, Future<*>>()

    fun register(id: String, fileName: String, dotSource: String = "", options: RunOptions = RunOptions(), originalPrompt: String = "", displayName: String = "", familyId: String = ""): PipelineState {
        val state = PipelineState()
        state.pipelineName.set(displayName.ifBlank { fileName.removeSuffix(".dot") })
        entries[id] = PipelineEntry(id, fileName, state, dotSource, options, originalPrompt = originalPrompt, displayName = displayName, familyId = familyId)
        orderedIds.add(id)
        store.insert(id, fileName, dotSource, options.simulate, options.autoApprove, originalPrompt, displayName, familyId)
        return state
    }

    fun registerFuture(id: String, future: Future<*>) {
        futures[id] = future
    }

    fun setLogsRoot(id: String, logsRoot: String) {
        entries.computeIfPresent(id) { _, entry -> entry.copy(logsRoot = logsRoot) }
        store.updateLogsRoot(id, logsRoot)
    }

    fun updateDotAndPrompt(id: String, dotSource: String, originalPrompt: String) {
        entries.computeIfPresent(id) { _, entry -> entry.copy(dotSource = dotSource, originalPrompt = originalPrompt) }
        store.updateDotAndPrompt(id, dotSource, originalPrompt)
    }

    fun setFamilyId(id: String, familyId: String) {
        entries.computeIfPresent(id) { _, entry -> entry.copy(familyId = familyId) }
    }

    fun cancel(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state.cancelToken.set(true)
        futures.remove(id)?.cancel(true)
        return true
    }

    fun pause(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state.pauseToken.set(true)
        return true
    }

    fun archive(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state.archived.set(true)
        store.archiveRun(id)
        return true
    }

    fun unarchive(id: String): Boolean {
        val entry = entries[id] ?: return false
        entry.state.archived.set(false)
        store.unarchiveRun(id)
        return true
    }

    /** Permanently removes a pipeline from memory and DB. Returns (success, logsRoot). */
    fun delete(id: String): Pair<Boolean, String> {
        val entry = entries[id] ?: return Pair(false, "")
        val logsRoot = entry.logsRoot
        entries.remove(id)
        orderedIds.remove(id)
        futures.remove(id)
        store.deleteRun(id)
        return Pair(true, logsRoot)
    }

    fun get(id: String): PipelineEntry? = entries[id]

    /**
     * Returns the entry if already in memory, or hydrates it from the DB and adds it
     * to the registry (marked view-only). Idempotent — safe to call concurrently.
     */
    fun getOrHydrate(id: String, store: RunStore): PipelineEntry? {
        entries[id]?.let { return it }
        val run = store.getById(id) ?: return null
        val entry = hydrateEntry(run).also { it.isHydratedViewOnly = true }
        synchronized(this) {
            if (!entries.containsKey(id)) {
                entries[id] = entry
                orderedIds.add(id)
            }
        }
        return entries[id]
    }

    fun getAll(): List<PipelineEntry> = orderedIds.mapNotNull { entries[it] }

    private fun hydrateEntry(run: attractor.db.StoredRun): PipelineEntry {
        val state = PipelineState()
        val displayStatus = if (run.status == "running") "failed" else run.status
        state.status.set(displayStatus)
        state.pipelineName.set(run.displayName.ifBlank { run.fileName.removeSuffix(".dot") })
        state.runId.set(run.id)
        state.archived.set(run.archived)
        state.startedAt.set(run.createdAt)
        if (run.finishedAt > 0L) state.finishedAt.set(run.finishedAt)

        if (run.pipelineLog.isNotBlank()) {
            run.pipelineLog.split("\n").filter { it.isNotBlank() }.forEach {
                state.recentLogs.addLast(it)
                state.recentLogsSize.incrementAndGet()
            }
        }

        if (run.logsRoot.isNotBlank()) state.logsRoot = run.logsRoot

        if (run.logsRoot.isNotBlank()) {
            val checkpoint = Checkpoint.load(run.logsRoot)
            if (checkpoint != null) {
                try {
                    val graph = Parser.parse(run.dotSource)
                    graph.nodes.values.forEachIndexed { idx, node ->
                        val nodeStatus = if (node.id in checkpoint.completedNodes) "completed" else "pending"
                        val durationMs = checkpoint.stageDurations[node.id]
                        val hLog = run.logsRoot.isNotBlank() &&
                            java.io.File(run.logsRoot, "${node.id}/live.log").let { it.exists() && it.length() > 0 }
                        state.stages.add(StageRecord(idx, node.label, node.id, nodeStatus, durationMs = durationMs, hasLog = hLog))
                    }
                } catch (_: Exception) {}
                // Derive total duration from sum of step durations so the displayed
                // runtime equals actual execution time, not wall-clock (which would
                // include server downtime, pauses, etc.)
                val totalDuration = checkpoint.stageDurations.values.sum()
                if (totalDuration > 0L) {
                    state.finishedAt.set(run.createdAt + totalDuration)
                }
            }
        }

        return PipelineEntry(
            id = run.id,
            fileName = run.fileName,
            state = state,
            dotSource = run.dotSource,
            options = RunOptions(run.simulate, run.autoApprove),
            logsRoot = run.logsRoot,
            originalPrompt = run.originalPrompt,
            displayName = run.displayName,
            familyId = run.familyId
        )
    }

    /**
     * Reconstruct pipeline history from the database on startup.
     * Runs with "running" status are treated as crashed and shown as "failed".
     */
    fun loadFromDB(onUpdate: () -> Unit) {
        for (run in store.getAll()) {
            val entry = hydrateEntry(run)
            entries[run.id] = entry
            orderedIds.add(run.id)
        }
        onUpdate()
    }

    /**
     * Insert or replace an imported run into the in-memory registry.
     * Caller is responsible for persisting to the DB first.
     */
    fun upsertImported(run: attractor.db.StoredRun) {
        val entry = hydrateEntry(run)
        if (entries.containsKey(run.id)) {
            orderedIds.remove(run.id)
        }
        entries[run.id] = entry
        orderedIds.add(run.id)
    }
}
