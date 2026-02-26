# Sprint 008: Performance Optimization

## Overview

Seven sprints of feature work have accumulated performance anti-patterns that combine to generate
significant unnecessary CPU and I/O during active pipeline execution. The most impactful is the
**SSE broadcast storm**: every pipeline event — including `CheckpointSaved` after each stage —
triggers a full `allPipelinesJson()` serialization of all pipelines, with a filesystem syscall
(`File.exists()` + `File.length()`) per stage per pipeline, then fans out the result to all SSE
clients. On a 10-stage pipeline with one SSE client, that's 30+ full serializations just for
stage lifecycle events, each doing 10+ filesystem probes.

Secondary hotspots: the log ring-buffer uses an O(n) copy-on-write data structure with O(n) trim
operations; checkpoint files are pretty-printed (30-50% larger, slower to write); regex patterns
for DOT sanitization are compiled on every request; SQLite is unconfigured; and the pipeline
executor is unbounded.

This sprint attacks all of these systematically. All changes are internal — no interface changes,
no SSE contract changes, no new dependencies.

## Use Cases

1. **Active pipeline execution not CPU-bound by UI serialization**: A running 10-stage pipeline
   completes. Only ~20 broadcasts are sent (stage start/complete pairs) instead of ~30 (the
   `CheckpointSaved` events are filtered).

2. **Stage log icon appears correctly for both successful and failed stages**: The `hasLog` flag
   is computed once when a stage terminates (success or failure) and cached in `StageRecord`.
   `toJson()` reads a boolean field instead of hitting the filesystem.

3. **Log buffer operates at O(1) per entry**: 200 log entries are maintained with a deque and an
   atomic counter. No array copies, no O(n) traversals.

4. **Database performs well under concurrent read/write**: SQLite WAL mode allows readers to
   proceed concurrently with writers; NORMAL sync is safe with WAL.

5. **Checkpoint writes are fast**: Compact JSON (no pretty-printing) reduces write time and file
   size by 30-50% for typical contexts.

6. **DOT sanitization is cheap on repeat calls**: Three regex patterns are compiled once at class
   initialization, not per render request.

## Architecture

```
Hot path BEFORE this sprint
──────────────────────────────────────────────────────────────────────
Engine emits event  (e.g., CheckpointSaved)
  → PipelineRunner observer.onEvent()
    → state.update(event)                         ← COWAL write
    → onUpdate()                                  ← called for ALL events
      → broadcastUpdate()
        → allPipelinesJson()
          → registry.getAll()
          → for each entry: state.toJson(logsRoot)
            → for each stage: File.exists() + File.length()  ← 2 syscalls per stage!
        → for each SSE client: queue.offer(json)

10-stage pipeline, 1 client: 10 CheckpointSaved × (10 stages × 2 syscalls + JSON build) = 200+ syscalls

Hot path AFTER this sprint
──────────────────────────────────────────────────────────────────────
Engine emits event
  → PipelineRunner observer.onEvent()
    → state.update(event)                         ← COWAL write
    → if (event is CheckpointSaved) → return      ← SKIP broadcast
    → onUpdate()
      → broadcastUpdate()
        → allPipelinesJson()
          → for each entry: state.toJson(logsRoot)
            → for each stage: s.hasLog (boolean field) ← 0 syscalls
        → for each SSE client: queue.offer(json)

CheckpointSaved events: no longer trigger broadcasts.
hasLog: computed once on stage termination, cached forever.
```

## Implementation Plan

### Phase 1: Skip `broadcastUpdate()` for `CheckpointSaved` events (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Problem**: `onUpdate()` is called on every event including `CheckpointSaved`, which fires
after every stage. It is a housekeeping event — the UI already received the same information
from the `StageCompleted` event that fires in the same stage cycle.

**Tasks:**
- [ ] In `PipelineRunner.runPipeline()`, in the `engine.subscribe { event -> }` lambda, change
  the final `onUpdate()` call to skip for `CheckpointSaved`:
  ```kotlin
  engine.subscribe { event ->
      state.update(event)
      // ... existing PipelineCompleted/PipelineFailed/etc. handling ...
      when (event) {
          is PipelineEvent.CheckpointSaved -> { /* state updated; no broadcast needed */ }
          else -> onUpdate()
      }
  }
  ```
  `state.update(event)` is still called for ALL events — so the log line ("Checkpoint → nodeId")
  is written. Only the SSE broadcast is skipped.

---

### Phase 2: Eliminate filesystem probes from `toJson()` (~25%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt`
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Problem**: `PipelineState.toJson()` calls `File.exists()` and `File.length()` for every stage
on every JSON serialization. This is the most expensive per-call cost during active pipelines.

**Solution**: Store `hasLog` in `StageRecord`, computed once when a stage terminates. Use an
`@Volatile var logsRoot` in `PipelineState` so the `update()` method can access it.

**Tasks in `PipelineState.kt`:**
- [ ] Add `val hasLog: Boolean = false` to the `StageRecord` data class
- [ ] Add `@Volatile var logsRoot: String = ""` to `PipelineState` class (not the constructor)
- [ ] Extract a private helper `fun checkHasLog(nodeId: String): Boolean`:
  ```kotlin
  private fun checkHasLog(nodeId: String): Boolean {
      val lr = logsRoot
      return lr.isNotBlank() && nodeId.isNotBlank() &&
          java.io.File(lr, "$nodeId/live.log").let { it.exists() && it.length() > 0 }
  }
  ```
- [ ] In `update()`, `StageCompleted` handler: set `hasLog`:
  ```kotlin
  is PipelineEvent.StageCompleted -> {
      updateStage(event.name, "running") { it.copy(status = "completed", durationMs = event.durationMs, hasLog = checkHasLog(it.nodeId)) }
      log("[${event.index}] ✓ ${event.name} (${event.durationMs}ms)")
  }
  ```
- [ ] In `update()`, `StageFailed` handler: set `hasLog` (failed stages also write `live.log`):
  ```kotlin
  is PipelineEvent.StageFailed -> {
      val updated = updateStageAny(event.name) { it.copy(status = "failed", error = event.error, hasLog = checkHasLog(it.nodeId)) }
      if (!updated) stages.add(StageRecord(event.index, event.name, status = "failed", error = event.error))
      log("[${event.index}] ✗ ${event.name}: ${event.error}")
  }
  ```
- [ ] In `update()`, `RepairFailed` handler: set `hasLog` on the repair-failed stage:
  ```kotlin
  is PipelineEvent.RepairFailed -> {
      val idx = stages.indexOfLast { it.name == event.stageName }
      if (idx >= 0) stages[idx] = stages[idx].copy(status = "failed", error = event.reason, hasLog = checkHasLog(stages[idx].nodeId))
      log("[${event.stageIndex}] ✗ Repair failed: ${event.stageName}: ${event.reason}")
  }
  ```
- [ ] In `toJson()`, replace the per-stage `File.exists()` / `File.length()` call:
  ```kotlin
  // Before:
  val hasLog = logsRoot.isNotBlank() && s.nodeId.isNotBlank() &&
      java.io.File(logsRoot, "${s.nodeId}/live.log").let { it.exists() && it.length() > 0 }
  sb.append("{...\"hasLog\":$hasLog")

  // After:
  sb.append("{...\"hasLog\":${s.hasLog}")
  ```
  Remove the `logsRoot: String = ""` parameter from `toJson()` since it is no longer needed
  for filesystem probes. Keep it as a no-op parameter for one sprint to avoid changing callers
  simultaneously — or remove it and update callers:
  - [ ] Remove `logsRoot` parameter from `toJson()` signature
  - [ ] Update all call sites: `entry.state.toJson(entry.logsRoot)` → `entry.state.toJson()`
    (there are ~4 call sites in `WebMonitorServer.kt`)

**Tasks in `PipelineRegistry.kt`:**
- [ ] In `hydrateEntry()`, populate `hasLog` correctly when building stage records from checkpoint:
  ```kotlin
  val hLog = run.logsRoot.isNotBlank() &&
      java.io.File(run.logsRoot, "$nodeId/live.log").let { it.exists() && it.length() > 0 }
  state.stages.add(StageRecord(idx, node.label, nodeId, nodeStatus, durationMs = durationMs, hasLog = hLog))
  ```
  (This still checks the filesystem, but it's done once at hydration, not on every broadcast.)
- [ ] After creating the `state` in `hydrateEntry()`, set `state.logsRoot = run.logsRoot`
- [ ] In `PipelineRunner.runPipeline()`, after `registry.setLogsRoot(id, logsRoot)`:
  ```kotlin
  registry.get(id)?.state?.logsRoot = logsRoot
  ```

---

### Phase 3: Fix log trimming — `ConcurrentLinkedDeque` + atomic counter (~15%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt`

**Problem**:
```kotlin
val recentLogs = CopyOnWriteArrayList<String>()
// In log():
while (recentLogs.size > 200) recentLogs.removeAt(0)  // O(n) copy per removal
```
`CopyOnWriteArrayList.removeAt(0)` copies the entire backing array. `size` on `CopyOnWriteArrayList`
is O(1), but each `removeAt(0)` is O(n). Under bursty events, the loop runs multiple iterations.

**Solution**: `ConcurrentLinkedDeque<String>` provides O(1) `addLast()` and `pollFirst()`. The
one subtlety: `ConcurrentLinkedDeque.size` is O(n) (traversal). We use a companion `AtomicInteger`
counter to track size at O(1).

**Tasks:**
- [ ] Replace `val recentLogs = CopyOnWriteArrayList<String>()` with:
  ```kotlin
  val recentLogs = java.util.concurrent.ConcurrentLinkedDeque<String>()
  private val recentLogsSize = java.util.concurrent.atomic.AtomicInteger(0)
  ```
- [ ] Update `log()`:
  ```kotlin
  private fun log(msg: String) {
      recentLogs.addLast("[${Instant.now()}] $msg")
      if (recentLogsSize.incrementAndGet() > 200) {
          recentLogs.pollFirst()
          recentLogsSize.decrementAndGet()
      }
  }
  ```
- [ ] Update `toJson()` log section — `takeLast(50)` doesn't exist on `Deque`:
  ```kotlin
  val logList = recentLogs.toList()
  val logsToShow = if (logList.size > 50) logList.subList(logList.size - 50, logList.size) else logList
  logsToShow.forEachIndexed { i, l ->
      if (i > 0) sb.append(",")
      sb.append(js(l))
  }
  ```
- [ ] Update `reset()`:
  ```kotlin
  recentLogs.clear()
  recentLogsSize.set(0)
  ```
- [ ] Update `PipelineRunner.kt` — `state.recentLogs.joinToString("\n")` still works on `Deque`. ✓
- [ ] Update `PipelineRegistry.hydrateEntry()`:
  ```kotlin
  run.pipelineLog.split("\n").filter { it.isNotBlank() }.forEach {
      state.recentLogs.addLast(it)
      state.recentLogsSize.incrementAndGet()
  }
  ```
  Make `recentLogsSize` `internal` (not `private`) so `hydrateEntry()` can access it, or add a
  `fun addLog(msg: String)` method to `PipelineState` for external use.

  **Simpler**: Make `recentLogsSize` `internal` — it's in the same package.

---

### Phase 4: Configure SQLite for performance (~10%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] After `val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")`, run pragmas:
  ```kotlin
  conn.createStatement().use { stmt ->
      stmt.execute("PRAGMA journal_mode=WAL")
      stmt.execute("PRAGMA synchronous=NORMAL")
      stmt.execute("PRAGMA cache_size=-32000")   // 32 MB (negative = KB)
      stmt.execute("PRAGMA busy_timeout=5000")
      stmt.execute("PRAGMA temp_store=MEMORY")
  }
  ```
  Place this BEFORE the existing `CREATE TABLE IF NOT EXISTS` setup block.

---

### Phase 5: Compact checkpoint JSON (~5%)

**Files:**
- `src/main/kotlin/attractor/state/Checkpoint.kt`

**Tasks:**
- [ ] In the `Checkpoint` class, change the instance `json` field:
  ```kotlin
  private val json = Json { prettyPrint = false }   // was: prettyPrint = true
  ```
  The companion object's `load` json already uses `{ ignoreUnknownKeys = true }` without
  pretty-printing; this change makes `save` match. Existing checkpoint files with pretty-printed
  JSON are still loaded correctly — `ignoreUnknownKeys = true` handles extra whitespace tokens.

---

### Phase 6: Cache compiled regex patterns (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Extract regex patterns to companion object (or private class-level vals):
  ```kotlin
  private companion object {
      val dotSanitizePass1 = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"(?:[^"\\]|\\.)*"""")
      val dotSanitizePass2 = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"[^"]*\z""")
      val dotSanitizePass3 = Regex("""\[\s*,\s*""")
  }
  ```
- [ ] Update `sanitizeDotForRender()`:
  ```kotlin
  private fun sanitizeDotForRender(dot: String): String {
      var result = dot.replace(dotSanitizePass1, "")
      result = result.replace(dotSanitizePass2, "")
      result = result.replace(dotSanitizePass3, "[")
      return result
  }
  ```

---

### Phase 7: Bound pipeline executor thread pool (~5%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Problem**: `Executors.newCachedThreadPool()` for the pipeline executor creates unlimited threads.
For the HTTP server we keep `newCachedThreadPool()` (SSE connections are long-lived blocking loops
that occupy one thread each; bounding this pool risks starving API requests when all threads are
held by SSE clients).

**Tasks:**
- [ ] In `PipelineRunner`, replace the executor:
  ```kotlin
  private val executor = Executors.newFixedThreadPool(
      (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(4)
  )
  ```
  This bounds concurrent pipeline runs to 2× CPU core count (minimum 4). For a local development
  tool this is well above typical usage and prevents thread explosion from programmatic pipeline
  submission.
- [ ] `resubmit()` and `resumePipeline()` both use the same executor — no changes needed there.

---

### Phase 8: Testing (~10%)

**Files:**
- `src/test/kotlin/attractor/web/PipelineStateTest.kt` (create)
- `src/test/kotlin/attractor/engine/EngineTest.kt` (modify if exists)

**Tasks:**
- [ ] Create `PipelineStateTest.kt`:
  - Test: `recentLogs` never exceeds 200 entries under burst logging
  - Test: `recentLogsSize` stays in sync with `recentLogs.size` after concurrent updates
  - Test: `toJson()` does not reference filesystem (validate `hasLog` is `false` by default, `true` after a `StageCompleted` event that sets `logsRoot` + creates the file)
  - Test: `hasLog` is set to `true` on `StageFailed` when log file exists
- [ ] Verify build passes after all changes

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Skip `onUpdate()` for `CheckpointSaved`; bound executor |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | `hasLog` in `StageRecord`; `logsRoot` field; `ConcurrentLinkedDeque` + atomic counter; remove FS probe from `toJson()` |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Populate `hasLog` in `hydrateEntry()`; set `state.logsRoot` |
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | SQLite PRAGMAs at connection init |
| `src/main/kotlin/attractor/state/Checkpoint.kt` | Modify | `prettyPrint = false` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Cache compiled regex patterns |
| `src/test/kotlin/attractor/web/PipelineStateTest.kt` | Create | Guard log buffer and `hasLog` behavior |

## Definition of Done

- [ ] `broadcastUpdate()` is NOT called for `CheckpointSaved` events; `state.update()` still IS
- [ ] `PipelineState.toJson()` does NOT call `File.exists()` or `File.length()`
- [ ] `StageRecord.hasLog` is `true` when `live.log` exists, for both completed and failed stages
- [ ] `hasLog` is set correctly on `StageCompleted`, `StageFailed`, and `RepairFailed`
- [ ] `hydrateEntry()` sets `hasLog` from filesystem (once, at hydration time, not per-broadcast)
- [ ] `PipelineState.logsRoot` is set at pipeline start and used in stage-termination handlers
- [ ] `recentLogs` is a `ConcurrentLinkedDeque<String>` with a companion `AtomicInteger` size counter
- [ ] Log trimming uses `pollFirst()` and atomic counter — no `removeAt(0)` or while loop
- [ ] SQLite connection initializes with: WAL, NORMAL sync, 32 MB cache, 5s busy_timeout, MEMORY temp
- [ ] `Checkpoint.save()` uses `prettyPrint = false`
- [ ] Three regex patterns in `sanitizeDotForRender` compiled once as companion object vals
- [ ] `PipelineRunner.executor` is a fixed thread pool (2×CPU min 4); HTTP executor unchanged
- [ ] `PipelineStateTest.kt` exists with log-cap, hasLog, and toJson-no-FS-hit tests
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] Dashboard loads; SSE delivers updates; log icons appear for completed AND failed stages
- [ ] No regressions: pause, resume, cancel, iterate, version history, history navigation,
  export/import, failure report, artifact browser

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Skipping `CheckpointSaved` broadcast delays dashboard: the next broadcast is `StageStarted` of the next stage (or pipeline terminal event) — effectively immediate | Very Low | Low | `StageCompleted` fires just before `CheckpointSaved`; client already has up-to-date stage status |
| `hasLog` is `false` for a failed stage that wrote `live.log` before the fix | Resolved | — | `StageFailed` and `RepairFailed` handlers now call `checkHasLog()` |
| `PipelineState.logsRoot` not set before first stage events fire | Low | Low | `setLogsRoot` + `state.logsRoot = logsRoot` is called before `engine.run()` in `runPipeline()` |
| SQLite WAL pragma fails on read-only filesystem or locked DB | Very Low | Low | Wrapped in `conn.createStatement()` — if it throws, app still starts; add `runCatching {}` if needed |
| `ConcurrentLinkedDeque.toList()` in `toJson()` creates a snapshot | Low | Low | `toList()` is safe; it creates a point-in-time copy for consistent iteration |
| `AtomicInteger recentLogsSize` drifts from actual deque size under extreme concurrency | Very Low | Low | Log trimming is only called from one thread per pipeline (the engine subscriber); concurrent reads never mutate |
| Fixed thread pool for pipeline executor blocks new pipeline submission when all threads busy | Very Low | Low | `2×CPU min 4` is generous for a local tool; Executors.newFixedThreadPool uses an unbounded queue by default |

## Security Considerations

- No new endpoints, file-serving, or external integrations added
- SQLite WAL mode does not change the local-only access model
- `ConcurrentLinkedDeque` does not expose one pipeline's log data to another
- Regex pre-compilation has no security implications

## Dependencies

- Sprint 007 (completed) — event model (all events including `CheckpointSaved` remain in use)
- No external dependencies

## Open Questions

1. Should terminal pipeline events (`PipelineCompleted`, `PipelineFailed`) bypass any future
   coalescing and always flush immediately? Current answer: yes — they are already handled
   synchronously in the `when (event)` block in `PipelineRunner`, which calls `onUpdate()`.
2. Should `allPipelinesJson()` be optimized further in a future sprint (dirty-fragment caching,
   exclude `dotSource` from incremental SSE broadcasts)? Deferred to Sprint 009 if needed.
3. Should checkpoints be retained in pretty-printed format for developer inspection? Users can
   always run `python3 -m json.tool checkpoint.json` to view them.
