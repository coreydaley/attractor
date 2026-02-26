# Sprint 001: Realtime Pipeline Status Updates

## Overview

The web dashboard currently has two mechanisms to show live pipeline state: SSE push and HTTP
polling. Both are unreliable in subtle but important ways that cause the UI to miss intermediate
stage transitions — particularly for fast pipelines.

The SSE path suffers from TCP buffering. Java's built-in `ChunkedOutputStream` collects writes in a
4096-byte buffer. The heartbeat is sent every 15 seconds, which leaves the TCP connection cold for
that full interval. Nagle's algorithm on the server socket means that small event payloads may not
leave the kernel's TX buffer until the next write arrives. The net effect: the browser may not see
an SSE event until 15 seconds after it was produced.

The polling path fails because it starts at a 2-second interval and only switches to 300 ms after
it observes `status === 'running'`. When a pipeline is submitted, a one-shot `fetch` fires but does
not reset the ongoing 2-second `setTimeout` chain. A fast simulation pipeline (< 2 s total) can
complete between two polling calls, leaving the UI in `idle` then `completed` with no intermediate
`running` or stage states ever shown. There is also no SSE reconnect logic — if the connection
drops the client is permanently degraded.

This sprint fixes all three root causes with minimal, targeted changes: a more aggressive server
heartbeat, a robust client-side SSE reconnect loop, and an immediate poll kickoff after submit.

## Use Cases

1. **Live stage tracking**: User uploads a pipeline, immediately sees it appear as `running`, and
   watches each stage appear as `running` then transition to `completed` or `failed` in real time.
2. **Fast pipeline visibility**: A simulated 5-node pipeline that completes in under 1 second still
   shows all stage transitions (even if briefly) before the final `completed` status.
3. **Resilient connection**: User puts their laptop to sleep mid-run and reopens it. The SSE
   reconnects, receives the current full state snapshot, and resumes updating.
4. **Log streaming**: The live log panel appends lines as they arrive without scroll jumping.
5. **Final-state reliability**: Even if SSE never works (e.g., proxy strips keep-alive), polling at
   300 ms reliably reflects final state within half a second of completion.

## Architecture

### Root Cause Map

```
PROBLEM: UI doesn't update during a pipeline run
│
├─ SSE events not delivered promptly
│   ├─ 15 s heartbeat leaves TCP cold → Nagle buffers small writes
│   └─ No client reconnect on disconnect
│
└─ Polling doesn't enter 300 ms mode fast enough
    ├─ Poll loop is at 2 s when pipeline is submitted
    └─ One-shot fetch after upload doesn't reset the poll timer
```

### Fix Map

```
FIX 1 (server): Heartbeat every 1 s instead of 15 s
    → keeps TCP warm, flushes ChunkedOutputStream buffer regularly
    → every heartbeat is a flush-through; Nagle can't hold small events

FIX 2 (client): After upload, call poll() directly (not a one-shot fetch)
    → poll() schedules the NEXT call using the detected running/idle rate
    → if pipeline is already running, next call is in 300 ms
    → cancels any outstanding slow-poll timer via a generation counter

FIX 3 (client): SSE reconnect with exponential backoff (max 5 s)
    → on error, close and reopen EventSource after a delay
    → on reconnect, EventSource sends initial snapshot automatically
    → connection indicator shows live/reconnecting

FIX 4 (server): Add X-Accel-Buffering: no header to SSE response
    → tells nginx/proxies not to buffer SSE; no-op if no proxy present
```

### Data Flow (after fix)

```
Engine (executor thread)
  │  emit(StageStarted)
  ▼
PipelineEventBus.emit()
  │  synchronous callback
  ▼
PipelineRunner subscription
  │  state.update(event)     → PipelineState updated atomically
  │  onUpdate()              → broadcastUpdate()
  ▼
WebMonitorServer.broadcastUpdate()
  │  allPipelinesJson()
  │  writeEvent(each SSE client)   ← synchronized(ex)
  ▼
Browser (EventSource)
  │  onmessage → applyUpdate(data)
  ▼
DOM update (renderTabs + renderMain)

═══ PARALLEL: heartbeat thread ═══
WebMonitorServer /events handler thread
  └─ Thread.sleep(1_000)          ← was 15_000
     writeEvent(heartbeat)        ← keeps TCP warm
```

## Implementation Plan

### Phase 1: Server-side SSE reliability (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Change heartbeat interval from `15_000` to `1_000` ms in the `/events` handler loop
- [ ] Add `X-Accel-Buffering: no` header to the SSE response in the `/events` handler
- [ ] Verify `writeEvent` still uses the trailing `: \n\n` flush trick (already present — keep it)

**Change (line ~187 in WebMonitorServer.kt):**
```kotlin
// before
Thread.sleep(15_000)

// after
Thread.sleep(1_000)
```

**Change (line ~178, add header):**
```kotlin
ex.responseHeaders.add("X-Accel-Buffering", "no")
```

### Phase 2: Client-side poll kickoff after submit (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS, ~lines 820-870)

**Problem:**
```javascript
// Current: one-shot fetch, doesn't reset the poll timer
fetch('/api/pipelines').then(r => r.json()).then(applyUpdate);
```

**Fix — add `pollGen` counter to cancel the outstanding slow timer and restart:**
```javascript
// Add at top of script block
var pollGen = 0;  // incremented on each kickoff to cancel in-flight slow timers

function poll(gen) {
  if (gen !== pollGen) return;  // stale timer, discard
  fetch('/api/pipelines')
    .then(function(r) { return r.json(); })
    .then(function(data) {
      if (gen !== pollGen) return;
      applyUpdate(data);
      var running = Object.keys(pipelines).some(function(id) {
        return pipelines[id].state && pipelines[id].state.status === 'running';
      });
      setTimeout(function() { poll(pollGen); }, running ? 300 : 2000);
    })
    .catch(function() {
      if (gen === pollGen) setTimeout(function() { poll(pollGen); }, 2000);
    });
}

function kickPoll() {
  pollGen++;
  poll(pollGen);
}
```

**Replace the one-shot fetch in both upload handlers:**
```javascript
// was: fetch('/api/pipelines').then(...).then(applyUpdate)
// now:
kickPoll();
```

**Replace initial `poll()` call:**
```javascript
// was: poll();
// now:
kickPoll();
```

### Phase 3: Client-side SSE reconnect (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS, ~lines 640-665)

**Current (no reconnect):**
```javascript
var evtSrc = new EventSource('/events');
evtSrc.onopen = function() { setConnected(true); };
evtSrc.onmessage = function(e) { try { applyUpdate(JSON.parse(e.data)); } catch (x) {} };
evtSrc.onerror = function() { setConnected(false); };
```

**Fix — reconnect with exponential backoff:**
```javascript
var sseDelay = 500;

function connectSSE() {
  var es = new EventSource('/events');
  es.onopen = function() {
    setConnected(true);
    sseDelay = 500;  // reset backoff on success
  };
  es.onmessage = function(e) {
    try { applyUpdate(JSON.parse(e.data)); } catch (x) {}
  };
  es.onerror = function() {
    setConnected(false);
    es.close();
    setTimeout(connectSSE, sseDelay);
    sseDelay = Math.min(sseDelay * 2, 5000);  // cap at 5 s
  };
}
connectSSE();
```

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Heartbeat interval, proxy header, poll kickoff, SSE reconnect |

Only one file changes. All fixes are in `WebMonitorServer.kt`.

## Definition of Done

- [ ] Heartbeat interval changed to 1 000 ms
- [ ] `X-Accel-Buffering: no` header added to SSE response
- [ ] `pollGen` counter and `kickPoll()` function replace one-shot fetch in all upload handlers
- [ ] `connectSSE()` reconnect function replaces bare `new EventSource('/events')`
- [ ] Manual test: upload `examples/simple.dot --simulate`, observe all stages appear live
- [ ] Manual test: pipeline runs to completion, status badge updates to `completed`
- [ ] Manual test: close browser tab mid-run, reopen, verify reconnect and state refresh
- [ ] Build succeeds: `gradle jar` produces `build/libs/coreys-attractor-1.0.0.jar`

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| 1 s heartbeat increases server thread count (one thread per SSE client) | Low | Low | `newCachedThreadPool` handles this; N clients = N sleeping threads, acceptable |
| `pollGen` counter races with SSE reconnect | Low | Low | Both mechanisms call `applyUpdate`; idempotent merge, no conflict |
| `X-Accel-Buffering` not recognized by some proxies | Very low | None | Header is advisory only; worst case it's ignored |
| Fast pipeline still completes before first 300 ms poll | Low | Low | SSE (now more reliable) and the immediate `kickPoll()` both fire at submit time |

## Security Considerations

- No new network surfaces opened
- SSE reconnect uses the same origin as the page; no CORS issue
- `pollGen` counter is purely client-side, no server state change

## Dependencies

None — no prior sprints.

## Open Questions

1. Should the 1 s heartbeat be configurable via a server parameter? (Probably not for now — 1 s is sensible default.)
2. Is there a better flush mechanism for `ChunkedOutputStream` that doesn't rely on timing? (Out of scope — TCP_NODELAY would require reflection hacks; the 1 s heartbeat is sufficient.)
3. Should we add an end-to-end test that runs a pipeline and asserts SSE events arrive? (Out of scope for this sprint — no test infrastructure exists yet.)
