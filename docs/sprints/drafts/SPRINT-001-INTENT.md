# Sprint 001 Intent: Realtime Pipeline Status Updates

## Seed

fix the web view of a pipeline run so that it displays the results of the run in realtime

## Context

coreys-attractor is a Kotlin DOT-pipeline runner with an embedded web dashboard (`WebMonitorServer.kt`).
When a `.dot` pipeline is submitted, the engine executes stages sequentially, emitting events
(`PipelineEvent`) onto a bus. A subscriber in `PipelineRunner` updates an in-memory `PipelineState`
and calls `broadcastUpdate()` after each event to push state to all SSE clients.

The dashboard relies on two mechanisms:
1. **SSE push** (`GET /events`) — server-sent events, ideally delivers state immediately
2. **Adaptive HTTP polling** (`GET /api/pipelines`) — 300 ms while running, 2 s otherwise

Both are broken or unreliable in the current implementation:
- SSE uses Java's built-in `com.sun.net.httpserver.ChunkedOutputStream` (4096-byte buffer, 15 s heartbeat). Due to Nagle's algorithm, small writes are batched by TCP until the buffer is flushed or the connection is warmed. The 15 s heartbeat gap leaves the connection cold.
- Polling never gets into 300 ms mode fast enough. The loop starts at 2 s; after upload a one-shot fetch triggers `applyUpdate`, but the ongoing poll timer is still ~2 s away. If a fast pipeline finishes within that 2 s window, the UI never sees any intermediate states.
- No SSE reconnect logic in the frontend. If the SSE connection drops, the client never recovers.

## Recent Sprint Context

No prior sprints in this project — this is Sprint 001.

## Relevant Codebase Areas

| File | Role |
|------|------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | HTTP server, SSE endpoint, `broadcastUpdate()`, all embedded HTML/JS |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Subscribes to engine events, calls `onUpdate()` (→ `broadcastUpdate()`) |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Per-pipeline in-memory state, `toJson()` |
| `src/main/kotlin/attractor/events/PipelineEvent.kt` | Event hierarchy + `PipelineEventBus` |
| `src/main/kotlin/attractor/engine/Engine.kt` | Emits `StageStarted`, `StageCompleted`, etc. |

## Constraints

- Must stay within the existing `com.sun.net.httpserver` stack (no new HTTP libraries)
- All HTML/JS is embedded as a Kotlin string in `WebMonitorServer.kt` — no separate asset files
- Must not break the existing polling fallback or the SSE initial snapshot
- No new Gradle dependencies

## Success Criteria

1. A user uploads a `.dot` pipeline and immediately sees the tab appear and status change to `running`
2. Each stage transition (started → completed/failed) is reflected in the stage list within ~500 ms
3. The live log panel appends new lines as they arrive
4. When the pipeline completes or fails, the status badge updates immediately
5. If the SSE connection drops and reconnects, state is refreshed correctly

## Verification Strategy

- Manual: run `examples/simple.dot --simulate` via web upload; observe all stages appear and update
- Manual: run a multi-node branching pipeline; verify stage order and status are correct
- Manual: close/reopen browser tab mid-run; verify reconnect and full state refresh
- No automated test framework exists in this project; verification is behavioral

## Uncertainty Assessment

- Correctness uncertainty: **Low** — the fix is targeted; existing event/state machinery is correct
- Scope uncertainty: **Low** — clearly bounded to SSE reliability and polling timing
- Architecture uncertainty: **Low** — staying within the existing HTTP server framework

## Open Questions

1. Should SSE flushing be improved via heartbeat frequency, padding, or a direct socket flush?
2. Should the client-side poll loop be restarted immediately after upload (vs. one-shot fetch)?
3. Should a periodic ticker replace the engine-event-only broadcast model for added robustness?
