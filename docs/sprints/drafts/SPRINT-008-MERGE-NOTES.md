# Sprint 008 Merge Notes

## Claude Draft Strengths
- Detailed, specific Phase 2 implementation with exact Kotlin code for `logsRoot` threading
- Precise SQLite PRAGMA values (WAL, NORMAL sync, 32MB cache, 5s busy_timeout, MEMORY temp)
- Specific `ConcurrentLinkedDeque` replacement strategy for `recentLogs`
- Concrete Phase 5 checkpoint compaction approach
- Regex caching approach with exact companion-object vals

## Codex Draft Strengths
- Better architecture diagram with "Before vs After" hot path
- Phase 1 coalescing/dirty-tracking — more ambitious but valuable for the largest bottleneck
- Phase 6 testing/benchmarking phase — missing from Claude draft entirely
- Context snapshot optimization (Phase 3) — avoids redundant map copying in Checkpoint.create()
- Flags SSE starvation risk with fixed thread pool (needs separate SSE vs API executor)
- Cleaner, actionable phase structure

## Valid Critiques Accepted

1. **Don't skip InterviewStarted/Completed broadcasts** — User confirmed "Skip only CheckpointSaved"
   in the interview. My draft text mentioned skipping interview events; that text is removed.

2. **`hasLog` must be set on `StageFailed` too** — Failed stages produce `live.log`. Setting
   `hasLog` only on `StageCompleted` would cause the log icon to never appear for failed stages.
   Fix: also set `hasLog` on `StageFailed` and `RepairFailed` events.

3. **HTTP thread pool SSE starvation** — Fixed pool with SSE long-lived blocking loops risks
   starving API handlers. Fix: Use separate thread pools — a bounded pool for the HTTP executor
   (used by short-lived API handlers) and keep the blocking SSE handler on its own dedicated pool,
   OR use the original cached thread pool for SSE threads and bound only the pipeline execution pool.
   Simpler correct fix: keep the HTTP executor as `newCachedThreadPool()` (SSE connections are
   long-lived but lightweight); what we should bound is the `PipelineRunner` executor.

4. **Codex's Phase 6 testing tasks accepted** — The Claude draft had no testing phase.

## Critiques Rejected

1. **SSE broadcast coalescing / dirty-fragment caching (Codex Phase 1)** — While compelling, this is
   significantly more complex (requires dirty flags, version counters, fragment cache invalidation)
   and introduces stale-data risk. The simpler `CheckpointSaved` skip + `toJson()` optimization
   is the right first step. Coalescing can be a follow-on sprint. The user asked for "optimal
   without breaking things" — aggressive caching could introduce subtle staleness bugs.

2. **Context snapshot optimization** — The checkpoint overhead from `context.snapshot()` is
   `O(n)` where n = number of context variables. For typical pipelines this is trivially small.
   The real bottleneck is the filesystem probes in `toJson()`, not context copying. Defer.

## Interview Refinements Applied

- Skip broadcast only for `CheckpointSaved` (not interview events) ✓
- Compact checkpoint JSON ✓
- Bound `PipelineRunner` executor; keep HTTP executor unbounded (avoids SSE starvation) ✓

## Final Decisions

1. **Broadcast filter**: Skip `onUpdate()` only for `CheckpointSaved` events
2. **`hasLog`**: Set on `StageCompleted`, `StageFailed`, and `RepairFailed` (all terminal stage transitions)
3. **`recentLogs`**: Replace COWAL with `ConcurrentLinkedDeque`; use atomic counter for O(1) size tracking
4. **SQLite**: WAL + NORMAL sync + 32MB cache + 5s busy_timeout + MEMORY temp
5. **Checkpoint**: `prettyPrint = false`
6. **Regex**: Compile once as companion object vals
7. **Thread pool**: Bound `PipelineRunner.executor` to `2×CPU min 4`; HTTP executor stays `newCachedThreadPool()`
8. **Testing**: Add `PipelineStateTest` to guard log buffer and `hasLog` behavior
