# Critique: SPRINT-001-CLAUDE-DRAFT

## Overall Assessment

Claude's draft is technically strong and tightly scoped to the sprint intent. It correctly identifies the three core failure modes (SSE delivery latency, polling cadence lag after submit, and missing reconnect behavior) and proposes concrete fixes in the right area of the codebase (`WebMonitorServer.kt`).

## What Works Well

1. Root-cause analysis is specific and maps cleanly to implementation changes.
2. The plan stays within constraints: no new dependencies, no HTTP stack replacement, embedded JS remains in Kotlin string.
3. Poll reset (`kickPoll`) + timer invalidation strategy (`pollGen`) is a practical fix for the current stale timeout problem.
4. SSE reconnect loop with capped backoff is a meaningful resilience upgrade.
5. Proposed changes are mostly localized to one file, which keeps sprint risk low.

## Gaps / Concerns

1. **Over-confident transport claims**: Statements like "Nagle can't hold small events" after 1s heartbeat are too absolute. Heartbeat improves odds, but does not guarantee immediate flush across all runtime/network conditions.
2. **Heartbeat default is aggressive without justification**: `1_000 ms` may be fine locally, but the draft does not compare 1s vs 2-3s tradeoffs (CPU/wakeups/thread churn vs latency).
3. **`X-Accel-Buffering` may be unnecessary for current deployment model**: It is harmless, but likely irrelevant unless users run behind nginx. Draft should frame it as optional hardening, not core fix.
4. **Reconnect plan misses explicit state resync fetch**: It relies on SSE initial snapshot implicitly. Adding an explicit `fetch('/api/pipelines')` on reconnect/open would improve convergence guarantees.
5. **Definition of Done is implementation-heavy**: Several DoD items are code-level tasks (e.g., exact variable names) rather than user-observable outcomes. Sprint DoD should emphasize behavior first.
6. **Build command inconsistency**: Uses `gradle jar`; repository has `./gradlew`, so verification should use wrapper for reproducibility.

## Recommended Adjustments

1. Reword transport guarantees to probabilistic/behavioral language ("sub-second in normal local conditions").
2. Set heartbeat target as a tunable decision point (default 2-3s unless 1s is empirically needed).
3. Keep `X-Accel-Buffering` as optional defense-in-depth.
4. Add explicit snapshot fetch on SSE reconnect/open.
5. Update DoD to prioritize the sprint intent success criteria (upload visibility, stage updates, log streaming, terminal status, reconnect recovery).
6. Use `./gradlew` commands in verification steps.

## Verdict

The draft is implementation-ready and directionally correct. With the adjustments above, it would better match sprint-planning standards (behavioral acceptance criteria, calibrated claims, and clearer prioritization between required fixes and optional hardening).
