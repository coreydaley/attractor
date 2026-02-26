# Sprint 008 Intent: Performance Optimization

## Seed

look for any ways that we can optimize this application, cpu, memory, speed, are we doing things
the optimal way to reduce cpu and memory but still run pipelines quickly?

## Context

Coreys-attractor is a Kotlin-based DOT pipeline runner with a web dashboard. Pipelines are
executed in a thread pool; the web dashboard receives real-time updates via Server-Sent Events
(SSE). After 7 sprints of feature work, no performance-oriented review has been done. The
application has accumulated several patterns that individually are reasonable but combine to create
significant unnecessary CPU and I/O load — especially during active pipeline execution.

## Recent Sprint Context

- **Sprint 005** — Pipeline version history: added `pipeline_family_id`, made runs immutable,
  introduced `getByFamilyId()` DB call on every family panel load.
- **Sprint 006** — History navigation: added `getOrHydrate()`, on-demand DB loads, version
  navigator. `allPipelinesJson()` now serializes `isHydratedViewOnly` per entry.
- **Sprint 007** — Intelligent failure diagnosis: added 5 new `PipelineEvent` types
  (`DiagnosticsStarted/Completed`, `RepairAttempted/Succeeded/Failed`), each triggering
  `broadcastUpdate()` in the existing `onUpdate()` chain.

## Relevant Codebase Areas

| Area | File | Issue |
|------|------|-------|
| SSE broadcast | `WebMonitorServer.kt:1129` | Serializes ALL pipelines on every event |
| JSON serialization | `PipelineState.toJson()` | Filesystem check per stage per serialization |
| Log trimming | `PipelineState.log()` | O(n) `removeAt(0)` in while loop on COWAL |
| SQLite | `RunStore.kt` | No WAL mode, no pragmas, single conn |
| Checkpoint | `Checkpoint.save()` | `prettyPrint=true` (slow), full context copy per stage |
| Regex | `WebMonitorServer.sanitizeDotForRender()` | 3 regex patterns compiled on every call |
| Thread pool | `WebMonitorServer.kt:29` | Unbounded `newCachedThreadPool()` for HTTP |
| Event dispatch | `PipelineRunner.kt:178` | `onUpdate()` (→ full serialize) called on ALL events |
| Broadcast volume | `allPipelinesJson()` | Sends full `dotSource` for every pipeline on every SSE push |

## Constraints

- Must follow project conventions in CLAUDE.md
- Must integrate with existing architecture — no new Gradle dependencies
- Must not change the SSE contract (clients get `{pipelines:[...]}` shaped data)
- Must not regress any existing features (pause, resume, cancel, iterate, version history, etc.)
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`

## Success Criteria

1. During active pipeline execution, CPU usage from serialization/broadcast drops measurably
2. The `toJson()` method no longer hits the filesystem on every call
3. Log trimming no longer does O(n) array copies in a loop
4. SQLite performance improves for concurrent read/write scenarios
5. Checkpoint files are written faster (compact JSON)
6. Regex patterns used for DOT sanitization are compiled once, not repeatedly
7. No regressions in any existing test or observable behavior

## Verification Strategy

- Spec/documentation: no external spec; verify against existing behavior
- Build passes after every change
- Manual test: run a pipeline, verify SSE updates arrive, verify dashboard remains functional
- Edge cases: empty pipelines list, single pipeline, many pipelines (10+), restart with DB history

## Uncertainty Assessment

- Correctness uncertainty: **Low** — optimizations are mechanical; each change is independently verifiable
- Scope uncertainty: **Low** — the issues are well-defined and bounded
- Architecture uncertainty: **Low** — all changes are internal; no interface changes

## Open Questions

1. Should `broadcastUpdate()` be debounced (coalesce rapid events)? Or just skip low-value events like `CheckpointSaved`?
2. Should `dotSource` be removed from `allPipelinesJson()` SSE broadcasts, given the client already has it? Or would this break the initial-snapshot use case?
3. Should the HTTP executor be bounded or remain unbounded (it's a local tool)?
