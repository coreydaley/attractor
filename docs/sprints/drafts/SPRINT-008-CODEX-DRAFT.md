# Sprint 008: Runtime Performance and Throughput Optimization

## Overview

After seven feature-focused sprints, the runtime now does significantly more work per stage event than
the user-visible behavior requires. The biggest issue is update amplification in the web monitor path:
every `PipelineEvent` triggers `broadcastUpdate()`, which rebuilds full JSON for all pipelines and
re-runs per-stage filesystem checks. This happens even for high-frequency events like
`CheckpointSaved`, causing avoidable CPU and I/O pressure while pipelines are active.

This sprint optimizes internal mechanics without changing product behavior. We keep the existing SSE
shape (`{"pipelines":[...]}`), preserve pause/resume/cancel/iterate/history flows, and focus on
mechanical improvements: coalesced SSE broadcasts, serialization caching, O(1) log buffering,
checkpoint write slimming, precompiled regexes, bounded executors, and SQLite pragmas for better
concurrent read/write performance.

The objective is to reduce CPU and disk churn during active execution while keeping the dashboard real
time and preserving the current architecture and dependency set.

## Use Cases

1. **Single active run with frequent stage events**: The dashboard remains responsive while CPU usage
   drops because event bursts are coalesced and JSON is not regenerated redundantly.
2. **Multiple concurrent pipelines (10+)**: SSE updates continue to flow without thread explosion or
   repeated full-state recomputation per tiny event.
3. **Long-running pipeline with heavy logging/checkpoints**: Checkpoints and state logs are written
   faster with less memory copying and fewer O(n) operations.
4. **History-heavy session with DB reads while runs are active**: SQLite read responsiveness improves
   under concurrent writes through WAL and tuned sync/cache pragmas.
5. **DOT render repair endpoints under repeated use**: DOT sanitization avoids repeated regex
   compilation and cuts per-request overhead.

## Architecture

### Hot Path Before

```text
Engine emits event
  -> PipelineRunner subscriber
      -> state.update(event)
      -> onUpdate()
          -> WebMonitorServer.broadcastUpdate()
              -> allPipelinesJson()
                  -> state.toJson(logsRoot) for every pipeline
                      -> File.exists()/length() per stage (hasLog)
              -> queue full payload to every SSE client
```

### Hot Path After (Target)

```text
Engine emits event
  -> PipelineRunner subscriber
      -> state.update(event)
      -> mark pipeline dirty + request broadcast
          -> WebMonitorServer coalesces updates (short debounce window)
              -> regenerate JSON only when dirty
              -> use cached per-pipeline JSON fragments
              -> no per-serialization filesystem probe loops
              -> push same payload to all clients
```

### Design Principles

- Preserve wire contract and feature behavior.
- Optimize by reducing repeated work first (coalescing, caching, precompiled patterns).
- Keep changes local to existing modules (`web`, `state`, `db`, `engine`).
- Add small focused tests for regression-sensitive logic.

## Implementation Plan

### Phase 1: SSE Broadcast Coalescing and Serialization Caching (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`
- `src/main/kotlin/attractor/web/PipelineRunner.kt`
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Replace immediate broadcast fan-out with short-window coalescing in `WebMonitorServer`
      (single scheduled flush for bursty updates).
- [ ] Add dirty/version tracking so `allPipelinesJson()` only rebuilds changed pipeline fragments.
- [ ] Cache the last full payload string and skip rebuild if no pipeline changed since last flush.
- [ ] Keep initial `/events` snapshot behavior unchanged and preserve full `{"pipelines":[...]}` shape.
- [ ] Ensure resume/rerun/archive/unarchive/import paths still mark registry entries dirty.

### Phase 2: PipelineState Data-Path Optimization (~25%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt`

**Tasks:**
- [ ] Replace O(n) log trimming loop on `CopyOnWriteArrayList` with a bounded O(1) ring buffer/deque.
- [ ] Remove filesystem probing from `toJson()` hot path (`File.exists()/length()` per stage).
- [ ] Track `hasLog` as stage metadata updated at event boundaries (or via cheap cached probe with
      invalidation), not on every serialization.
- [ ] Add lightweight JSON caching in `PipelineState` invalidated only on state mutation.
- [ ] Preserve existing JSON fields and stage status semantics.

### Phase 3: Checkpoint and Context Snapshot Efficiency (~15%)

**Files:**
- `src/main/kotlin/attractor/state/Checkpoint.kt`
- `src/main/kotlin/attractor/state/Context.kt`
- `src/main/kotlin/attractor/engine/Engine.kt`

**Tasks:**
- [ ] Switch checkpoint writes to compact JSON (`prettyPrint=false`) to reduce write volume.
- [ ] Remove redundant map/list conversions during checkpoint serialization where possible.
- [ ] Introduce a checkpoint-focused context snapshot path to avoid extra copying/boxing.
- [ ] Keep resume compatibility for existing `checkpoint.json` data.
- [ ] Confirm checkpoint-save frequency/placement remains behaviorally equivalent.

### Phase 4: SQLite and Executor Tuning (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Tasks:**
- [ ] Configure SQLite pragmas at startup (`journal_mode=WAL`, `synchronous=NORMAL`,
      `temp_store=MEMORY`, `busy_timeout`, cache tuning).
- [ ] Keep existing `@Synchronized` API behavior while improving concurrent reader/writer throughput.
- [ ] Replace unbounded `newCachedThreadPool()` with bounded executors for HTTP handling and pipeline
      run submission.
- [ ] Verify cancellation/resume semantics still work with bounded pools.

### Phase 5: Regex and Event-Noise Cleanup (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Tasks:**
- [ ] Precompile DOT sanitization regex patterns once as class-level constants.
- [ ] Evaluate event-trigger filtering/coalescing policy for low-value high-frequency updates
      (`CheckpointSaved`) while preserving user-visible logs.
- [ ] Ensure diagnosis/repair events still appear promptly in UI.

### Phase 6: Validation and Benchmarking (~5%)

**Files:**
- `src/test/kotlin/attractor/engine/EngineTest.kt`
- `src/test/kotlin/attractor/web/PipelineStateTest.kt` (new)
- `src/test/kotlin/attractor/db/RunStoreTest.kt` (new, if needed)

**Tasks:**
- [ ] Add `PipelineState` tests for bounded logs and serialization behavior (no FS hit regression path).
- [ ] Add/extend tests for checkpoint compatibility and compact JSON persistence.
- [ ] Add targeted `RunStore` test for pragma-safe startup and basic concurrent access behavior.
- [ ] Manual performance verification with active pipelines (1, 5, 10+) and SSE-connected dashboard.
- [ ] Run full build/test command from intent constraints.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Coalesced SSE, payload caching, precompiled regex, bounded HTTP executor |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Event update policy integration and bounded run executor |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Dirty/version metadata for pipeline JSON caching |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | O(1) bounded logs, hasLog strategy, JSON caching |
| `src/main/kotlin/attractor/state/Checkpoint.kt` | Modify | Compact checkpoint writes, reduced serialization overhead |
| `src/main/kotlin/attractor/state/Context.kt` | Modify | Checkpoint-oriented snapshot optimization |
| `src/main/kotlin/attractor/engine/Engine.kt` | Modify | Integrate checkpoint snapshot optimization without behavior changes |
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | SQLite WAL/pragmas for better mixed workload performance |
| `src/test/kotlin/attractor/web/PipelineStateTest.kt` | Create | Guard log buffer and serialization-path performance fixes |
| `src/test/kotlin/attractor/db/RunStoreTest.kt` | Create (optional) | Validate DB initialization pragmas and basic store behavior |
| `src/test/kotlin/attractor/engine/EngineTest.kt` | Modify | Checkpoint compatibility/perf-path regression coverage |

## Definition of Done

- [ ] SSE still emits `{"pipelines":[...]}` and dashboard behavior is unchanged.
- [ ] Broadcast/serialization CPU load drops measurably during active execution.
- [ ] `PipelineState.toJson()` no longer performs per-stage filesystem probes on each call.
- [ ] Log buffering no longer uses O(n) repeated `removeAt(0)` behavior.
- [ ] SQLite runs in WAL mode with tuned pragmas and no functional regressions.
- [ ] Checkpoint files are written as compact JSON and resume still works.
- [ ] DOT sanitization regexes are compiled once, not per request.
- [ ] Build/test command from sprint intent succeeds.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Coalescing makes UI feel delayed | Medium | Medium | Keep debounce window short (tens of ms) and flush immediately for terminal events |
| JSON/stage caching serves stale data | Medium | High | Strict dirty invalidation on every state mutation; tests for stage/log consistency |
| Bounded executors throttle throughput too aggressively | Low | Medium | Start with conservative bounds and queue; tune with manual load tests |
| SQLite pragma compatibility differences by environment | Low | Medium | Apply pragmas defensively; keep fallback behavior if pragma call fails |
| Checkpoint format changes break resume on old files | Low | High | Maintain backward-compatible decoder and add resume compatibility tests |

## Security Considerations

- No new external dependencies or network paths added.
- Caching changes must not expose one pipeline’s data in another pipeline’s payload.
- Executor and queue changes must avoid unbounded memory growth under client disconnect/backpressure.

## Dependencies

- Builds on sprint 005/006/007 data model and event surface.
- No new Gradle dependencies.

## Open Questions

1. Should `CheckpointSaved` continue producing immediate SSE updates, or be fully coalesced?
2. Should terminal pipeline events bypass coalescing and flush immediately?
3. Do we keep `dotSource` in every incremental SSE payload for now, then revisit in a future sprint?
