package attractor.events

import attractor.state.Outcome
import java.time.Instant

/**
 * Sealed hierarchy of all pipeline lifecycle events (Section 9.6).
 */
sealed class PipelineEvent {
    abstract val timestamp: Instant

    // ── Pipeline lifecycle ───────────────────────────────────────────────────

    data class PipelineStarted(
        val name: String,
        val id: String,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class PipelineCompleted(
        val durationMs: Long,
        val artifactCount: Int = 0,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class PipelineFailed(
        val error: String,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class PipelineCancelled(
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class PipelinePaused(
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    // ── Stage lifecycle ──────────────────────────────────────────────────────

    data class StageStarted(
        val name: String,
        val index: Int,
        val nodeId: String = "",
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class StageCompleted(
        val name: String,
        val index: Int,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class StageFailed(
        val name: String,
        val index: Int,
        val error: String,
        val willRetry: Boolean = false,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class StageRetrying(
        val name: String,
        val index: Int,
        val attempt: Int,
        val delayMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    // ── Parallel execution ───────────────────────────────────────────────────

    data class ParallelStarted(
        val branchCount: Int,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class ParallelBranchStarted(
        val branch: String,
        val index: Int,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class ParallelBranchCompleted(
        val branch: String,
        val index: Int,
        val durationMs: Long,
        val success: Boolean,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class ParallelCompleted(
        val durationMs: Long,
        val successCount: Int,
        val failureCount: Int,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    // ── Human interaction ────────────────────────────────────────────────────

    data class InterviewStarted(
        val questionText: String,
        val stage: String,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class InterviewCompleted(
        val questionText: String,
        val answer: String,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    data class InterviewTimeout(
        val questionText: String,
        val stage: String,
        val durationMs: Long,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()

    // ── Checkpoint ───────────────────────────────────────────────────────────

    data class CheckpointSaved(
        val nodeId: String,
        override val timestamp: Instant = Instant.now()
    ) : PipelineEvent()
}

/**
 * Observer that receives pipeline events.
 */
fun interface PipelineEventObserver {
    fun onEvent(event: PipelineEvent)
}

/**
 * Event bus for distributing pipeline events to multiple observers.
 */
class PipelineEventBus {
    // CopyOnWriteArrayList: safe for concurrent subscribe/emit across engine sub-threads
    private val observers = java.util.concurrent.CopyOnWriteArrayList<PipelineEventObserver>()

    fun subscribe(observer: PipelineEventObserver) {
        observers.add(observer)
    }

    fun unsubscribe(observer: PipelineEventObserver) {
        observers.remove(observer)
    }

    fun emit(event: PipelineEvent) {
        observers.forEach { it.onEvent(event) }
    }
}
