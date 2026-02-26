# Sprint 008: Performance Optimization

## Overview

Seven sprints of feature work have built up a set of performance anti-patterns that, while
individually reasonable, combine to generate significant unnecessary CPU and I/O load during
active pipeline execution. The most egregious is the **SSE broadcast storm**: every pipeline
event — including low-value `CheckpointSaved` events that fire after every stage — triggers a
full serialization of all pipelines' state, including filesystem probes for each stage's log
file, and pushes the resulting JSON to all connected SSE clients. On a 10-stage pipeline, that's
30+ full serializations just for stage lifecycle events, each doing dozens of filesystem checks.

This sprint attacks performance at every layer: SSE broadcast rate, JSON serialization I/O,
log data structure efficiency, SQLite configuration, checkpoint compactness, and regex
compilation. All changes are internal optimizations with no interface changes — the SSE contract,
all API shapes, and all observable behaviors remain identical.

The goal is to make the application feel snappier, reduce background CPU during pipeline
execution, and prevent the application from becoming sluggish as the number of historical runs
grows.

## Use Cases

1. **Active pipeline execution is not CPU-bound by UI serialization**: A running pipeline
   completes 10 stages. The pipeline thread is not blocked serializing all pipeline state for
   each stage event; the broadcast is coalesced.

2. **Stage log visibility doesn't require filesystem probes on every update**: The dashboard
   shows log icons without hitting the disk 10 times per SSE update.

3. **Database is configured for performance from first connection**: SQLite WAL mode, sensible
   cache and synchronous settings applied at startup.

4. **Log ring buffer doesn't do O(n) array copies**: 200 log entries are maintained with
   `ArrayDeque` without copying the entire array on each trim.

5. **Checkpoint writes are fast**: After each stage, the checkpoint is written as compact JSON
   (no pretty-printing), completing faster on disk.

6. **DOT sanitization regex patterns are compiled once**: The three regex patterns in
   `sanitizeDotForRender` are compiled at class initialization, not on every render call.

## Architecture

```
Current hot path per pipeline event:
─────────────────────────────────────────────────────────────────────────
Engine emits event
  → PipelineEventBus.emit()                          [synchronous]
    → PipelineRunner observer.onEvent()
      → state.update(event)                          [COWAL write × N]
      → onUpdate()                                   [called for ALL events]
        → broadcastUpdate()
          → allPipelinesJson()                       [serialize ALL pipelines]
            → for each entry: state.toJson(logsRoot)
              → for each stage: File.exists() + File.length()  ← I/O!
          → for each SSE client: queue.offer(json)

CheckpointSaved emits once per stage → 10 broadcasts for 10-stage pipeline
StageStarted, StageCompleted, CheckpointSaved = 3 events × 10 stages = 30 broadcasts

After optimization:
─────────────────────────────────────────────────────────────────────────
Engine emits event
  → PipelineEventBus.emit()                          [synchronous]
    → PipelineRunner observer.onEvent()
      → state.update(event)                          [COWAL write × N]
      → onUpdate() only for high-value events        [skip CheckpointSaved]
        → broadcastUpdate()
          → allPipelinesJson()                       [serialize ALL pipelines]
            → for each entry: state.toJson(logsRoot)
              → for each stage: check StageRecord.hasLog (in-memory flag)  ← no I/O!
          → for each SSE client: queue.offer(json)
```

## Implementation Plan

### Phase 1: Skip `broadcastUpdate()` for `CheckpointSaved` events (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Problem**: `onUpdate()` is called on every event including `CheckpointSaved`, which fires
after every stage completion. This triggers `broadcastUpdate()` → full JSON serialization.
`CheckpointSaved` is an engine-internal housekeeping event; the UI gets the same information
from `StageCompleted` that fires in the same stage cycle.

**Tasks:**
- [ ] In `PipelineRunner.runPipeline()`, in the engine event subscriber lambda, change
  `onUpdate()` to only be called for events that have visible UI effects. Skip `CheckpointSaved`
  and `InterviewStarted`/`InterviewCompleted` (the log update is sufficient; no panel re-render
  is needed for those events at broadcast granularity).
  ```kotlin
  // In the engine.subscribe block, at the end:
  when (event) {
      is PipelineEvent.CheckpointSaved -> { /* state already updated; no broadcast needed */ }
      else -> onUpdate()
  }
  ```
  Keep `state.update(event)` called for ALL events (so the log line is written and internal
  state stays current). Only skip the `onUpdate()` broadcast call.

**Impact**: For a 10-stage pipeline, reduces broadcasts from ~30 to ~20 (still get Started/
Completed/Failed per stage). For pipelines with retries or diagnosis, saves even more.

---

### Phase 2: Eliminate filesystem probes from `toJson()` (~25%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt`

**Problem**: `PipelineState.toJson()` contains:
```kotlin
val hasLog = logsRoot.isNotBlank() && s.nodeId.isNotBlank() &&
    java.io.File(logsRoot, "${s.nodeId}/live.log").let { it.exists() && it.length() > 0 }
```
This executes `File.exists()` and `File.length()` (two syscalls) for **every stage in every
pipeline on every JSON serialization**. With 10 stages and 3 SSE clients, each broadcast fires
30 filesystem syscalls before any actual writing. This runs at high frequency during active
execution.

**Solution**: Move `hasLog` into `StageRecord` as a mutable field, set it when the handler
writes the live.log (i.e., on `StageCompleted`) and never rechecked:

**Tasks:**
- [ ] Add `val hasLog: Boolean = false` to `StageRecord` data class
- [ ] In `PipelineState.update()`, `StageCompleted` handler: after calling `updateStage(...)`,
  also check and set `hasLog`:
  ```kotlin
  is PipelineEvent.StageCompleted -> {
      val logExists = logsRoot.isNotBlank() ...
      updateStage(event.name, "running") {
          it.copy(status = "completed", durationMs = event.durationMs,
                  hasLog = logsRoot.isNotBlank() && it.nodeId.isNotBlank() &&
                      java.io.File(logsRoot, "${it.nodeId}/live.log").let { f -> f.exists() && f.length() > 0 })
      }
  }
  ```
  Wait — `PipelineState.update()` doesn't receive `logsRoot`. We need another approach.

**Revised approach**: Pass `logsRoot` into `PipelineState` at construction time, or set it once
when the pipeline starts. `PipelineState` is created in `PipelineRegistry.register()` and the
`logsRoot` is set separately via `registry.setLogsRoot(id, logsRoot)` in `PipelineRunner`.

Instead of threading `logsRoot` through `PipelineState`, we keep `toJson(logsRoot)` signature
but move the filesystem check to a one-time population:

- [ ] Add `val hasLog: Boolean = false` to `StageRecord` (default false)
- [ ] In `PipelineState.toJson()`, remove the `File.exists()` probe:
  ```kotlin
  sb.append("{\"index\":${s.index},\"name\":${js(s.name)},\"nodeId\":${js(s.nodeId)},\"status\":${js(s.status)},\"hasLog\":${s.hasLog}")
  ```
- [ ] In `PipelineState.update()` for `StageCompleted`:
  The `logsRoot` is not available here. We add a separate method:
  ```kotlin
  fun markHasLog(nodeId: String) {
      val idx = stages.indexOfLast { it.nodeId == nodeId }
      if (idx >= 0) stages[idx] = stages[idx].copy(hasLog = true)
  }
  ```
- [ ] In `PipelineRunner.runPipeline()`, in the engine subscriber, after `state.update(event)`,
  for `StageCompleted`:
  ```kotlin
  is PipelineEvent.StageCompleted -> {
      // Find the nodeId for this stage name from the entry
      val nodeId = registry.get(id)?.let { entry ->
          entry.dotSource  // We'd need to parse... too expensive
      }
  }
  ```

  **Actually simpler**: In `PipelineState.update()` for `StageCompleted`, after updating the
  stage record to "completed", we do the log check inline with a callback or we store the
  logsRoot. Let's store it:

  **Final revised approach**:
- [ ] Add `@Volatile var logsRoot: String = ""` to `PipelineState`
- [ ] In `PipelineRunner.runPipeline()`, after `registry.setLogsRoot(id, logsRoot)`:
  ```kotlin
  state.logsRoot = logsRoot
  ```
- [ ] In `PipelineState.update()`, `StageCompleted` handler:
  ```kotlin
  is PipelineEvent.StageCompleted -> {
      val lr = logsRoot
      updateStage(event.name, "running") { rec ->
          val hLog = lr.isNotBlank() && rec.nodeId.isNotBlank() &&
              java.io.File(lr, "${rec.nodeId}/live.log").let { it.exists() && it.length() > 0 }
          rec.copy(status = "completed", durationMs = event.durationMs, hasLog = hLog)
      }
      log("[${event.index}] ✓ ${event.name} (${event.durationMs}ms)")
  }
  ```
  The filesystem check now runs once (on `StageCompleted`), not on every `toJson()` call.
- [ ] In `toJson()`, replace the per-stage `File.exists()` with `s.hasLog`
- [ ] In `hydrateEntry()` in `PipelineRegistry.kt`, when building stage records from checkpoint,
  populate `hasLog` correctly:
  ```kotlin
  val nodeId = node.id
  val hLog = run.logsRoot.isNotBlank() &&
      java.io.File(run.logsRoot, "$nodeId/live.log").let { it.exists() && it.length() > 0 }
  state.stages.add(StageRecord(idx, node.label, nodeId, nodeStatus, durationMs = durationMs, hasLog = hLog))
  ```
  This still checks the filesystem, but it happens once at hydration time, not on every SSE push.

---

### Phase 3: Fix log trimming — replace `CopyOnWriteArrayList` with `ArrayDeque` for `recentLogs` (~20%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt`

**Problem**:
```kotlin
val recentLogs = CopyOnWriteArrayList<String>()
...
private fun log(msg: String) {
    val entry = "[${Instant.now()}] $msg"
    recentLogs.add(entry)
    while (recentLogs.size > 200) recentLogs.removeAt(0)  // O(n) copy per removal!
}
```
- `CopyOnWriteArrayList.removeAt(0)` copies the entire backing array every call
- In a burst of events, this loop runs multiple times, each copying ~200 elements
- `CopyOnWriteArrayList` creates a new array on every write — ideal for "many reads, few writes"
  but the log is written on every event

**Solution**: Replace `recentLogs` with a `java.util.concurrent.ConcurrentLinkedDeque` (or
`ArrayDeque` wrapped with synchronization). Since `recentLogs` is only written from the engine
subscriber (one thread per pipeline) but read from the SSE serializer (a different thread),
we need thread-safety.

Use `ConcurrentLinkedDeque<String>` — it supports O(1) `addLast()` and `pollFirst()`, and is
concurrent-safe:

**Tasks:**
- [ ] Change `val recentLogs = CopyOnWriteArrayList<String>()` to
  `val recentLogs = java.util.concurrent.ConcurrentLinkedDeque<String>()`
- [ ] In `log()`:
  ```kotlin
  private fun log(msg: String) {
      recentLogs.addLast("[${Instant.now()}] $msg")
      while (recentLogs.size > 200) recentLogs.pollFirst()
  }
  ```
  This is O(1) per operation; no array copies.
- [ ] In `toJson()`: `recentLogs.takeLast(50)` doesn't exist on `Deque`. Replace with:
  ```kotlin
  val logList = recentLogs.toList()  // snapshot for consistent iteration
  val logsToShow = if (logList.size > 50) logList.subList(logList.size - 50, logList.size) else logList
  ```
- [ ] In `PipelineRunner.kt`:
  ```kotlin
  store.updateLog(id, state.recentLogs.joinToString("\n"))
  ```
  `ConcurrentLinkedDeque` has `joinToString`, so this still works. ✓
- [ ] In `reset()`:
  ```kotlin
  recentLogs.clear()  // ConcurrentLinkedDeque has clear()
  ```
- [ ] In `PipelineRegistry.hydrateEntry()`:
  ```kotlin
  run.pipelineLog.split("\n").filter { it.isNotBlank() }.forEach { state.recentLogs.addLast(it) }
  ```

---

### Phase 4: Configure SQLite for performance (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Problem**: SQLite defaults are tuned for safety, not performance. The connection has no
WAL mode, no cache tuning, and no busy timeout. For an in-process local tool, the tradeoffs
favor performance over durability.

**Tasks:**
- [ ] After `DriverManager.getConnection(...)`, run SQLite PRAGMAs:
  ```kotlin
  conn.createStatement().use { stmt ->
      stmt.execute("PRAGMA journal_mode=WAL")         // concurrent reads + faster writes
      stmt.execute("PRAGMA synchronous=NORMAL")        // safe with WAL; faster than FULL
      stmt.execute("PRAGMA cache_size=-32000")         // 32 MB page cache (negative = KB)
      stmt.execute("PRAGMA busy_timeout=5000")         // wait up to 5s if locked
      stmt.execute("PRAGMA temp_store=MEMORY")         // temp tables in memory
  }
  ```
  These are standard performance pragmas for SQLite single-writer single-reader workloads.
  WAL mode + NORMAL synchronous is the standard recommendation for local app databases.

---

### Phase 5: Compact JSON for checkpoints (~10%)

**Files:**
- `src/main/kotlin/attractor/state/Checkpoint.kt`

**Problem**: `Checkpoint.save()` uses `Json { prettyPrint = true }`. Pretty-printing adds
whitespace (indentation, newlines) to every field and array element. For a checkpoint with
a moderate context map, this could add 30-50% overhead to the output size and serialization
time. The checkpoint file is only read by the engine; humans don't need to read it during
normal operation.

**Tasks:**
- [ ] In the `Checkpoint` class, change the instance `json` field:
  ```kotlin
  private val json = Json { prettyPrint = false }  // was: prettyPrint = true
  ```
  The companion `load` json already uses `{ ignoreUnknownKeys = true }` without prettyPrint.

---

### Phase 6: Cache compiled regex patterns (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Problem**: `sanitizeDotForRender()` constructs and compiles three `Regex` objects every time
it is called (via `dot.replace(Regex(...))` inside the function body). These patterns don't
change between calls. Kotlin compiles `Regex(...)` to `java.util.regex.Pattern.compile()`, which
does full regex compilation on each call.

**Tasks:**
- [ ] Extract the three regex patterns to a companion object or top-level private vals:
  ```kotlin
  // In companion object or as private class-level vals:
  private val dotAttrsPassOne = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"(?:[^"\\]|\\.)*"""")
  private val dotAttrsPassTwo = Regex("""(?s),?\s*\b(prompt|goal|goal_gate)\s*=\s*"[^"]*\z""")
  private val dotAttrsPassThree = Regex("""\[\s*,\s*""")
  ```
- [ ] Update `sanitizeDotForRender()` to use the cached patterns:
  ```kotlin
  private fun sanitizeDotForRender(dot: String): String {
      var result = dot.replace(dotAttrsPassOne, "")
      result = result.replace(dotAttrsPassTwo, "")
      result = result.replace(dotAttrsPassThree, "[")
      return result
  }
  ```

---

### Phase 7: Bound the HTTP server thread pool (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Problem**: `httpServer.executor = Executors.newCachedThreadPool()` creates unlimited threads
for HTTP handling. For a local tool, this is fine under normal conditions, but under pathological
load (many concurrent SSE connections + many API calls), threads could pile up.

**Tasks:**
- [ ] Replace with a bounded pool that still handles concurrent SSE connections:
  ```kotlin
  httpServer.executor = Executors.newFixedThreadPool(
      (Runtime.getRuntime().availableProcessors() * 2).coerceAtLeast(8)
  )
  ```
  This allows 2× CPU count threads minimum 8, which handles multiple concurrent SSE streams
  plus API requests without creating unbounded threads.

  **Note**: Each SSE connection occupies one thread for its lifetime (it's a blocking loop in
  `httpServer.createContext("/events")`). With 8 threads min, up to ~5-6 SSE clients plus
  a few API requests can be handled concurrently, which is well beyond realistic local use.

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Skip `broadcastUpdate()` for `CheckpointSaved` events |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | Add `hasLog` to `StageRecord`; `logsRoot` field; replace COWAL with `ConcurrentLinkedDeque` for recentLogs; remove filesystem probe from `toJson()` |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Populate `hasLog` in `hydrateEntry()`; set `state.logsRoot` |
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add SQLite PRAGMAs at connection init |
| `src/main/kotlin/attractor/state/Checkpoint.kt` | Modify | Remove `prettyPrint = true` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Cache compiled regex; bound HTTP thread pool |

## Definition of Done

- [ ] `broadcastUpdate()` is NOT called for `CheckpointSaved` events; `state.update()` still is
- [ ] `PipelineState.toJson()` does NOT call `File.exists()` or `File.length()`
- [ ] `StageRecord` has a `hasLog: Boolean` field; it is set correctly on `StageCompleted`
- [ ] `hydrateEntry()` populates `hasLog` from the filesystem once at hydration time
- [ ] `PipelineState.logsRoot` is set when the pipeline starts and used in `StageCompleted` handling
- [ ] `recentLogs` is a `ConcurrentLinkedDeque<String>`; `removeAt(0)` loop is replaced with `pollFirst()`
- [ ] SQLite connection initializes with WAL, NORMAL sync, 32 MB cache, 5s busy timeout, MEMORY temp store
- [ ] `Checkpoint.save()` writes compact (non-pretty) JSON
- [ ] Three regex patterns in `sanitizeDotForRender` are compiled once as class-level vals
- [ ] HTTP server uses a bounded thread pool (2× CPU count, min 8)
- [ ] Build passes: `export JAVA_HOME=... && ~/.gradle/wrapper/.../gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] Dashboard loads and shows pipelines; SSE delivers real-time updates; log icons appear correctly
- [ ] No regressions: pause, resume, cancel, iterate, version history, history navigation,
  export/import, failure report

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Skipping `onUpdate()` for `CheckpointSaved` delays dashboard update by one event | Very Low | Low | `StageCompleted` fires immediately after `CheckpointSaved`; dashboard is effectively up-to-date |
| `ConcurrentLinkedDeque.size` is O(n) | Low | Low | Called only in `log()` trim check; the deque is capped at ≤201 elements, so traversal is trivially fast |
| SQLite WAL mode persists across restarts (it's a file-level setting) | Very Low | Low | WAL mode is sticky once set, but benign; re-setting it is idempotent and harmless |
| Bounded HTTP thread pool starves SSE connections during high API load | Very Low | Low | Pool is 2×CPU min 8; local tool usage will never saturate this |
| `PipelineState.logsRoot` field set after some events are emitted | Low | Low | `logsRoot` is set before engine runs, before any stage events fire |
| `hasLog` is false in `hydrateEntry()` if `logsRoot` changed after archival | Very Low | Low | Same as current behavior for hydrated entries |

## Security Considerations

- No new endpoints or file-serving surface added
- All changes are internal data structure / configuration improvements
- SQLite WAL mode does not change the access model; the DB file is still local-only

## Dependencies

- Sprint 007 (completed) — event model; `PipelineState.update()` handles all existing events
- No external dependencies

## Open Questions

1. Should `broadcastUpdate()` also be skipped for `DiagnosticsStarted` (it immediately emits
   `DiagnosticsCompleted` too, creating a double broadcast for diagnosis)? Proposal: skip
   `DiagnosticsStarted` broadcast, keep `DiagnosticsCompleted` since it has final data.
2. Should checkpoints remain human-readable (pretty JSON) for debugging? Counter: users can
   always run `python3 -m json.tool checkpoint.json` to pretty-print manually.
3. Should `allPipelinesJson()` be optimized to exclude `dotSource` from SSE broadcasts (only
   include it on the initial snapshot)? This would require client-side tracking and could break
   the existing simple merge logic. Defer to a future sprint.
