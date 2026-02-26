# Sprint 001: Realtime Pipeline Status Updates

## Overview

This sprint fixes realtime run visibility in the embedded web dashboard so stage and status updates appear as the pipeline executes, not seconds later or only at completion. Today, the dashboard has two intended live-update paths, SSE and adaptive polling, but both can lag in common cases: SSE can batch writes and polling can remain on a stale 2s cadence right after upload.

The scope is intentionally narrow: strengthen delivery reliability in the existing `com.sun.net.httpserver` + embedded JS architecture without adding new dependencies. Engine event generation and in-memory state updates already work; this sprint improves transport behavior and client timing so users actually see those updates in near-realtime.

## Use Cases

1. **Immediate run visibility after upload**: After submitting a `.dot` file from Upload or Create, a tab appears quickly and status flips to `running` without waiting for the next slow poll interval.
2. **Live stage progression**: `StageStarted` and `StageCompleted`/`StageFailed` transitions render in the stage list within roughly 500 ms.
3. **Incremental log streaming**: Log lines appended in `PipelineState` are reflected continuously in the Live Log panel.
4. **Terminal status freshness**: `completed` or `failed` state appears immediately when the run ends.
5. **Connection resilience**: If SSE disconnects (tab sleep/network hiccup), client reconnects and refreshes state automatically.

## Architecture

Current flow:

```text
Engine emits PipelineEvent
  -> PipelineRunner subscriber
    -> PipelineState.update(event)
      -> WebMonitorServer.broadcastUpdate()
        -> /events SSE clients

Frontend also polls /api/pipelines on a timer
```

Problems in current flow:

```text
Upload submitted
  -> one-shot fetch(/api/pipelines)
  -> existing poll loop may still be sleeping for ~2s
  -> fast run can complete before next fast (300ms) poll is scheduled

SSE connected
  -> small event writes may be coalesced
  -> heartbeat every 15s leaves connection cold
  -> if EventSource errors, UI marks reconnecting but has no explicit backoff/replace strategy
```

Target flow:

```text
Upload submitted
  -> trigger immediate polling cadence reset
  -> run enters rapid poll mode quickly

SSE connected
  -> maintain warm stream with shorter heartbeat + explicit flush-friendly framing
  -> on error, schedule reconnect with bounded backoff
  -> on reconnect/open, fetch snapshot to resync

Polling remains fallback and safety net if SSE is degraded
```

## Implementation Plan

### Phase 1: Server-side SSE Reliability (~45%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - SSE endpoint behavior, heartbeat cadence, write strategy

**Tasks:**
- [ ] Reduce heartbeat interval from 15s to a short keepalive interval (for example 2-3s) to keep stream active.
- [ ] Preserve/strengthen `writeEvent()` framing that forces chunk flush under `ChunkedOutputStream`.
- [ ] Ensure heartbeat writes and broadcast writes stay synchronized per-client and remove dead clients promptly.
- [ ] Keep initial SSE snapshot semantics unchanged (`writeEvent(ex, allPipelinesJson())` on connect).

### Phase 2: Frontend Reconnect + Poll Cadence Control (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` - embedded dashboard JS (`EventSource`, polling, upload/create submission handlers)

**Tasks:**
- [ ] Replace single `evtSrc` setup with managed connect/reconnect function.
- [ ] Add bounded reconnect backoff after `onerror`, recreating `EventSource` explicitly.
- [ ] On SSE open/reconnect, perform snapshot fetch (`/api/pipelines`) for state convergence.
- [ ] Refactor polling loop to support immediate cadence reset after upload/create submit success.
- [ ] Ensure only one active polling timer exists (avoid overlapping loops during resets).

### Phase 3: Behavioral Verification + Sprint Hygiene (~15%)

**Files:**
- `docs/sprints/drafts/SPRINT-001-CODEX-DRAFT.md` - finalized plan
- `docs/sprints/drafts/SPRINT-001-CLAUDE-DRAFT-CODEX-CRITIQUE.md` - comparative critique

**Tasks:**
- [ ] Manually verify `examples/simple.dot --simulate` updates stage-by-stage in near realtime.
- [ ] Verify a branching pipeline reflects stage order/status correctly.
- [ ] Verify tab close/reopen mid-run resynchronizes state.
- [ ] Record findings/decisions in sprint docs and convert to executable implementation tasks.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Improve SSE reliability and client reconnect/poll behavior in embedded JS |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Validate (no/low change expected) | Confirm event->state->broadcast pipeline remains correct |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Validate (no/low change expected) | Confirm stage/log serialization supports realtime UI refresh |
| `docs/sprints/drafts/SPRINT-001-CODEX-DRAFT.md` | Create | Codex sprint draft |
| `docs/sprints/drafts/SPRINT-001-CLAUDE-DRAFT-CODEX-CRITIQUE.md` | Create | Critique of alternate draft |

## Definition of Done

- [ ] Uploading a pipeline shows a tab and `running` state immediately.
- [ ] Stage transitions render within ~500 ms for normal local runs.
- [ ] Live log panel appends new lines during execution.
- [ ] Terminal status updates immediately at completion/failure.
- [ ] SSE disconnect triggers reconnect and full state resync.
- [ ] Existing polling fallback still works if SSE is unavailable.
- [ ] No new Gradle dependencies introduced.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Excessively frequent heartbeat increases server/client load | Low | Medium | Use modest keepalive (2-3s), tiny payload, validate with multiple tabs |
| Multiple polling timers after refactor cause request amplification | Medium | Medium | Centralize timer ownership and clear timer before rescheduling |
| Reconnect loop thrashes on persistent failure | Medium | Low | Bounded exponential backoff with max delay cap |
| SSE flush behavior varies by JDK/runtime | Low | Medium | Keep fallback polling authoritative and validate on target Java version |

## Security Considerations

- Preserve existing local-hosted trust model and CORS behavior; do not expand external exposure.
- Keep JSON serialization and HTML escaping behavior unchanged in user-visible fields.
- Avoid adding endpoints or request surface area; this sprint is transport reliability only.

## Dependencies

- No prior sprint dependency (Sprint 001).
- Runtime constraint: remain on existing `com.sun.net.httpserver` implementation.
- No new libraries or Gradle dependency changes.

## Open Questions

1. Should heartbeat interval be fixed (for example 2s) or configurable via environment variable?
2. Should reconnect backoff reset immediately on first successful message, or only after `onopen` + snapshot fetch success?
3. Do we want an optional periodic server broadcast ticker as defense-in-depth, or keep event-driven broadcast only?
