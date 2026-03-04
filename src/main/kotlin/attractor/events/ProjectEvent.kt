package attractor.events

import attractor.state.Outcome
import java.time.Instant

/**
 * Sealed hierarchy of all project lifecycle events (Section 9.6).
 */
sealed class ProjectEvent {
    abstract val timestamp: Instant

    // ── Project lifecycle ────────────────────────────────────────────────────

    data class ProjectStarted(
        val name: String,
        val id: String,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ProjectCompleted(
        val durationMs: Long,
        val artifactCount: Int = 0,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ProjectFailed(
        val error: String,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ProjectCancelled(
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ProjectPaused(
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    // ── Stage lifecycle ──────────────────────────────────────────────────────

    data class StageStarted(
        val name: String,
        val index: Int,
        val nodeId: String = "",
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class StageCompleted(
        val name: String,
        val index: Int,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class StageFailed(
        val name: String,
        val index: Int,
        val error: String,
        val willRetry: Boolean = false,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class StageRetrying(
        val name: String,
        val index: Int,
        val attempt: Int,
        val delayMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    // ── Parallel execution ───────────────────────────────────────────────────

    data class ParallelStarted(
        val branchCount: Int,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ParallelBranchStarted(
        val branch: String,
        val index: Int,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ParallelBranchCompleted(
        val branch: String,
        val index: Int,
        val durationMs: Long,
        val success: Boolean,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class ParallelCompleted(
        val durationMs: Long,
        val successCount: Int,
        val failureCount: Int,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    // ── Human interaction ────────────────────────────────────────────────────

    data class InterviewStarted(
        val questionText: String,
        val stage: String,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class InterviewCompleted(
        val questionText: String,
        val answer: String,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class InterviewTimeout(
        val questionText: String,
        val stage: String,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    // ── Checkpoint ───────────────────────────────────────────────────────────

    data class CheckpointSaved(
        val nodeId: String,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    // ── Failure diagnosis and repair ─────────────────────────────────────────

    data class DiagnosticsStarted(
        val nodeId: String,
        val stageName: String,
        val stageIndex: Int,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class DiagnosticsCompleted(
        val nodeId: String,
        val stageName: String,
        val stageIndex: Int,
        val recoverable: Boolean,
        val strategy: String,
        val explanation: String,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class RepairAttempted(
        val stageName: String,
        val stageIndex: Int,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class RepairSucceeded(
        val stageName: String,
        val stageIndex: Int,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()

    data class RepairFailed(
        val stageName: String,
        val stageIndex: Int,
        val reason: String,
        override val timestamp: Instant = Instant.now()
    ) : ProjectEvent()
}

/**
 * Observer that receives project events.
 */
fun interface ProjectEventObserver {
    fun onEvent(event: ProjectEvent)
}

/**
 * Event bus for distributing project events to multiple observers.
 */
class ProjectEventBus {
    // CopyOnWriteArrayList: safe for concurrent subscribe/emit across engine sub-threads
    private val observers = java.util.concurrent.CopyOnWriteArrayList<ProjectEventObserver>()

    fun subscribe(observer: ProjectEventObserver) {
        observers.add(observer)
    }

    fun unsubscribe(observer: ProjectEventObserver) {
        observers.remove(observer)
    }

    fun emit(event: ProjectEvent) {
        observers.forEach { it.onEvent(event) }
    }
}
