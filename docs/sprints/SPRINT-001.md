# Sprint 001: Realtime Pipeline Status Updates

## Overview

The web dashboard has two live-update paths — SSE push and HTTP polling — but both fail in common
cases, causing the UI to miss intermediate stage transitions or show stale state until the pipeline
completes.

The SSE path suffers from TCP buffering. Java's built-in `com.sun.net.httpserver` wraps responses
in a `ChunkedOutputStream` (4096-byte buffer). The heartbeat fires every 15 s, leaving the TCP
connection cold for that full interval. Nagle's algorithm on the server socket can coalesce small
event writes until the next heartbeat finally flushes them. Under typical conditions, events arrive
in bursts rather than as they happen.

The polling path fails because it starts at a 2-second interval and only drops to 300 ms after it
observes `status === 'running'`. A one-shot `fetch` fires after upload but does not reset the
ongoing timer. A fast simulation pipeline (< 2 s) can complete between two polling calls, so the
UI shows `idle` → `completed` with no `running` or stage states ever rendered.

This sprint fixes all three root causes — SSE delivery latency, polling cadence lag, and missing
reconnect — with changes to one file (`WebMonitorServer.kt`).

## Use Cases

1. **Immediate run visibility**: After submitting a `.dot` file, a tab appears and status changes to
   `running` without waiting for a slow poll cycle.
2. **Live stage progression**: `StageStarted` → `StageCompleted`/`StageFailed` transitions appear
   in the stage list within ~500 ms under normal local conditions.
3. **Incremental log streaming**: New log lines appear in the live log panel as they are produced.
4. **Terminal status freshness**: `completed` or `failed` badge updates promptly when the run ends.
5. **Connection resilience**: If the SSE connection drops (network hiccup, laptop sleep), the
   client reconnects automatically and refreshes state from a snapshot.

## Architecture

### Root Cause Map

```
PROBLEM: UI misses stage transitions during a pipeline run
│
├── SSE events not delivered promptly
│   ├── 15 s heartbeat leaves TCP connection cold
│   ├── Nagle's algorithm batches small writes (< 4 KB)
│   └── No client reconnect on disconnect
│
└── Polling doesn't enter 300 ms mode fast enough
    ├── Poll loop is at 2 s when pipeline is submitted
    └── One-shot fetch after upload doesn't reset the poll timer
```

### Fix Map

```
FIX 1 (server): Shared heartbeat scheduler — 2 s interval, one thread, all clients
    Shared ScheduledExecutorService fires every 2 s.
    Writes ": heartbeat\n\n" to every SSE client, flushes ChunkedOutputStream buffer.
    Dead clients are pruned by the scheduler. SSE handler thread just sleeps.

FIX 2 (server): Virtual thread executor for HTTP server
    httpServer.executor = Executors.newVirtualThreadPerTaskExecutor()
    Each request (including sleeping SSE handlers) gets a virtual thread → negligible cost.

FIX 3 (server): X-Accel-Buffering: no header (defense-in-depth)
    Tells nginx/proxies not to buffer SSE. No-op if no proxy; harmless either way.

FIX 4 (client): kickPoll() — immediate poll cadence reset on submit
    pollGen counter cancels any in-flight slow timer.
    poll(gen) discards stale calls.
    After upload, kickPoll() fires immediately → if pipeline is running, next call is 300 ms.

FIX 5 (client): connectSSE() — reconnect with exponential backoff + snapshot fetch
    On error, close EventSource and reopen after delay (500 ms → 5 s cap).
    On open, fetch /api/pipelines for state convergence.
    On message, apply update as before.
```

### Component Diagram (after fix)

```
                  ┌─────────────────────────────────┐
                  │        Engine (exec thread)      │
                  │  emit(StageStarted)              │
                  └──────────────┬──────────────────┘
                                 │ eventBus
                  ┌──────────────▼──────────────────┐
                  │    PipelineRunner subscription   │
                  │  state.update(event)             │
                  │  broadcastUpdate()               │
                  └──────────────┬──────────────────┘
                                 │ writeEvent() per SSE client
                  ┌──────────────▼──────────────────┐
                  │   WebMonitorServer SSE clients   │
                  │  CopyOnWriteArrayList<HttpExch.> │
                  └──────────────┬──────────────────┘
                                 │ SSE data: {...}\n\n
                  ┌──────────────▼──────────────────┐
                  │      Browser (EventSource)       │
                  │  onmessage → applyUpdate(data)   │
                  └──────────────┬──────────────────┘
                                 │ DOM update
                  ┌──────────────▼──────────────────┐
                  │  renderTabs() + renderMain()     │
                  └─────────────────────────────────┘

  PARALLEL: heartbeat scheduler (daemon, 1 thread)
  ┌──────────────────────────────────────────────────┐
  │  scheduleAtFixedRate(2 s)                        │
  │  → write ": heartbeat\n\n" to each SSE client   │
  │  → remove dead clients on write failure          │
  └──────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 1: Server-side SSE Reliability (~40%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `private val heartbeatScheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sse-heartbeat").also { it.isDaemon = true } }` field to `WebMonitorServer`
- [ ] In `init`, after SSE context creation, start scheduler: `heartbeatScheduler.scheduleAtFixedRate({ sendHeartbeats() }, 2, 2, TimeUnit.SECONDS)`
- [ ] Add `private fun sendHeartbeats()` that iterates `sseClients`, writes `": heartbeat\n\n"`, flushes, removes dead clients (mirrors current `broadcastUpdate` dead-client logic)
- [ ] In `/events` handler: remove the `while + Thread.sleep(15_000) + heartbeat write` loop; replace with `while (sseClients.contains(ex)) { Thread.sleep(2_000) }` (just keeps handler thread alive)
- [ ] Add `ex.responseHeaders.add("X-Accel-Buffering", "no")` to SSE endpoint headers
- [ ] Change `httpServer.executor = Executors.newCachedThreadPool()` to `Executors.newVirtualThreadPerTaskExecutor()`
- [ ] Add `java.util.concurrent.TimeUnit` import

**Key code — `sendHeartbeats()`:**
```kotlin
private fun sendHeartbeats() {
    val dead = mutableListOf<HttpExchange>()
    for (client in sseClients) {
        try {
            synchronized(client) {
                client.responseBody.write(": heartbeat\n\n".toByteArray())
                client.responseBody.flush()
            }
        } catch (_: Exception) {
            dead.add(client)
        }
    }
    sseClients.removeAll(dead.toSet())
}
```

**Key code — `/events` handler (simplified):**
```kotlin
httpServer.createContext("/events") { ex ->
    ex.responseHeaders.add("Content-Type", "text/event-stream")
    ex.responseHeaders.add("Cache-Control", "no-cache")
    ex.responseHeaders.add("Connection", "keep-alive")
    ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
    ex.responseHeaders.add("X-Accel-Buffering", "no")
    ex.sendResponseHeaders(200, 0)
    sseClients.add(ex)
    try {
        writeEvent(ex, allPipelinesJson())
        while (sseClients.contains(ex)) { Thread.sleep(2_000) }
    } catch (_: Exception) {
        // client disconnected
    } finally {
        sseClients.remove(ex)
    }
}
```

### Phase 2: Client-side Poll Cadence + SSE Reconnect (~60%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded JS, ~lines 482–878)

**Tasks:**
- [ ] Add `pollGen` counter variable at top of `<script>` block
- [ ] Replace `poll()` function with generation-aware version that discards stale timers
- [ ] Add `kickPoll()` function that increments `pollGen` and immediately calls `poll(pollGen)`
- [ ] In `submitUpload()` success handler: replace one-shot `fetch('/api/pipelines')...applyUpdate` with `kickPoll()`
- [ ] In `runGenerated()` success handler: same replacement
- [ ] Replace initial `poll()` call at bottom of script with `kickPoll()`
- [ ] Replace bare `new EventSource('/events')` setup with `connectSSE()` function
- [ ] `connectSSE()` must: on open — fetch snapshot; on message — applyUpdate; on error — close + reconnect with backoff

**Key JS — poll with generation counter:**
```javascript
var pollGen = 0;

function poll(gen) {
  if (gen !== pollGen) return;
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

**Key JS — SSE reconnect:**
```javascript
var sseDelay = 500;

function connectSSE() {
  var es = new EventSource('/events');
  es.onopen = function() {
    setConnected(true);
    sseDelay = 500;
    // Explicit snapshot fetch on (re)connect for state convergence
    fetch('/api/pipelines')
      .then(function(r) { return r.json(); })
      .then(applyUpdate)
      .catch(function() {});
  };
  es.onmessage = function(e) {
    try { applyUpdate(JSON.parse(e.data)); } catch (x) {}
  };
  es.onerror = function() {
    setConnected(false);
    es.close();
    setTimeout(connectSSE, sseDelay);
    sseDelay = Math.min(sseDelay * 2, 5000);
  };
}
connectSSE();
```

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | All changes: shared heartbeat scheduler, virtual thread executor, SSE headers, `kickPoll()`, `connectSSE()` |

Only one file changes.

## Definition of Done

- [ ] Upload a `.dot` pipeline from the web UI → tab appears and shows `running` within ~1 s
- [ ] Each stage appears as `running` then `completed` (or `failed`) without manual refresh
- [ ] Live log panel shows new lines as they are produced
- [ ] `completed`/`failed` badge updates promptly when run ends
- [ ] Closing and reopening the browser tab mid-run reconnects and shows current state
- [ ] Opening two browser tabs simultaneously: both receive updates (multi-tab validated)
- [ ] Build succeeds: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies introduced

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Virtual thread executor not available (Java < 21) | Low | Medium | Project requires Java 21 (per MEMORY); no mitigation needed |
| Shared heartbeat scheduler races with `broadcastUpdate` writing to same client | Low | Low | Both already use `synchronized(client)` |
| Dead-client pruning in `sendHeartbeats()` races with `broadcastUpdate` prune | Very low | None | Both use `CopyOnWriteArrayList.removeAll()` which is atomic; duplicate removes are safe |
| `pollGen` overflow after very long session | Very low | None | JS number type; overflows gracefully |
| SSE reconnect backoff resets on `onopen` but snapshot fetch fails | Low | Low | UI falls back to 300 ms poll immediately via `kickPoll` |

## Security Considerations

- No new network surfaces; same origin, same CORS policy
- `X-Accel-Buffering` header is advisory only; does not affect security posture
- Virtual thread executor changes threading model but not request handling logic

## Dependencies

None — first sprint.

## Open Questions

1. Virtual threads (`newVirtualThreadPerTaskExecutor`) are GA in Java 21. If a future deployment requires Java 17 compatibility, revert to `newCachedThreadPool()` — the rest of the changes are unaffected.
2. Heartbeat interval (2 s) is a fixed constant. If profiling shows excessive wakeups at high client counts, expose it as a constructor parameter.
