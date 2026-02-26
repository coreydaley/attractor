# Sprint 001 Merge Notes

## Claude Draft Strengths
- Precise TCP/Nagle root-cause analysis grounded in the actual code
- `pollGen` timer-invalidation pattern prevents overlapping poll loops
- Concrete SSE reconnect with capped exponential backoff
- Identified `X-Accel-Buffering` header as useful defense-in-depth

## Codex Draft Strengths
- Behavior-first DoD (not implementation variable names)
- More conservative 2-3s heartbeat recommendation (appropriate for multi-tab)
- Explicit snapshot fetch on SSE reconnect/open (improves state convergence)
- Risk call-out: multiple polling timers can amplify requests
- Security considerations section

## Valid Critiques Accepted
- **Heartbeat at 1s is aggressive for multi-tab**: User confirmed multiple tabs possible → using 2s default
- **Add explicit snapshot fetch on `onopen`**: Accepted — `fetch('/api/pipelines')` called in `connectSSE` on open event
- **DoD should be behavior-first**: Accepted — rewrote DoD around observable outcomes
- **Use `./gradlew`**: Noted (but MEMORY notes gradlew fails on Java 25 + Gradle 8.7; used native gradle command instead, documented in build notes)
- **`X-Accel-Buffering` is optional hardening**: Accepted — kept but marked as defense-in-depth

## Critiques Rejected
- **"Over-confident transport claims"**: The draft already uses conditional language; this was a style note, not a structural issue

## Interview Refinements Applied
- **Shared heartbeat ticker instead of per-client sleep**: `ScheduledExecutorService` fires every 2s, broadcasts heartbeat to all clients, removes dead ones. SSE handler thread just sleeps until removed from `sseClients`.
- **Virtual thread executor for HTTP server**: Java 21 supports `Executors.newVirtualThreadPerTaskExecutor()`, making sleeping threads essentially free. Use this for `httpServer.executor`.

## Final Decisions
1. Heartbeat: 2s via shared `ScheduledExecutorService` (daemon), NOT per-client sleep
2. HTTP executor: `Executors.newVirtualThreadPerTaskExecutor()` (Java 21)
3. SSE handler: sends initial snapshot, sleeps waiting for removal from sseClients
4. `X-Accel-Buffering: no` header: yes, kept as defense-in-depth
5. Poll reset: `kickPoll()` + `pollGen` counter (cancels stale timers)
6. SSE reconnect: `connectSSE()` + exponential backoff (500ms → 5s) + snapshot fetch on open
7. DoD: behavior-first per Codex recommendation
