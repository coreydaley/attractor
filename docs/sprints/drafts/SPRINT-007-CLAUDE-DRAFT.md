# Sprint 007: Intelligent Failure Diagnosis and Self-Healing

## Overview

When a pipeline stage exhausts its retry budget today, the engine emits `PipelineFailed` with the
raw exception string and halts. The failure reason is whatever the handler threw — a Java exception
message, an HTTP status code, or a truncated LLM response — with no deeper understanding of _why_
it failed or whether the situation is recoverable.

This sprint adds an **intelligent failure diagnosis layer** between "retries exhausted" and "halt."
After all normal retry and routing mechanisms have failed, the engine consults a `FailureDiagnoser`
service, which uses a lightweight LLM call to classify the failure, decide if it can be
auto-repaired, and optionally generate a "repair hint" that gets injected into context before a
single supervised repair attempt on the same stage.

If the repair attempt succeeds, the pipeline continues normally. If the failure is classified as
unrecoverable, or if the repair attempt also fails, the engine writes a structured
`failure_report.json` to the run's `logsRoot` and halts with an enriched error message. The web
monitor displays a "Diagnosing…" status during the analysis and shows the diagnosis explanation in
the pipeline log.

The mechanism degrades gracefully: in simulation mode or when no API key is present, the
`NullDiagnoser` skips the LLM call, writes a basic failure report, and halts — identical to the
current behavior but with a report file.

## Use Cases

1. **Transient LLM error recovery**: A `codergen` stage fails because the Anthropic API returned
   a 529 (overloaded). Normal retries also hit rate limits. The diagnoser recognizes the pattern,
   injects `repair.hint = "API was overloaded; wait and retry with a simpler prompt"` into context,
   and the repair attempt succeeds on a backoff.

2. **Malformed output self-correction**: A stage produces output that fails downstream validation.
   The diagnoser sees the validation error in the failure reason, classifies it as
   `RETRY_WITH_HINT`, and injects an explanation of what the output should look like. The stage
   retries with the corrected guidance and succeeds.

3. **Unrecoverable failure — clear report**: A stage tries to run a shell command on a path that
   doesn't exist. The diagnoser classifies this as `ABORT` (deterministic failure, no hint can
   fix a missing file). A `failure_report.json` is written to `logsRoot/` with the full analysis
   and the pipeline halts with a clear message.

4. **No API key / simulation mode**: Diagnoser is a `NullDiagnoser`. After stage failure, basic
   `failure_report.json` is written (no LLM analysis) and the pipeline halts exactly as it does
   today.

5. **Opt-out per node**: A node marked `failure_diagnosis_disabled=true` in its DOT attributes
   skips diagnosis entirely — useful for terminal/exit nodes where failure semantics are explicit.

## Architecture

```
Engine.runLoop()                           (existing)
  └── executeNodeWithRetry(node, ...)      (existing, retries per max_retries)
        └── [all retries exhausted → FAIL outcome]
              │
              ▼
  [Step 6: Select next edge]               (existing)
  [No edge + FAIL → retryTarget routing]   (existing)
  [No retryTarget]                         ← NEW integration point
              │
              ▼
  FailureDiagnoser.analyze(
    node, outcome, context, graph, logsRoot
  )
  ┌──────────────────────────────────────────────────────┐
  │ FailureAnalysis                                       │
  │   strategy: RETRY_WITH_HINT | SKIP | ABORT           │
  │   canFix: Boolean                                     │
  │   explanation: String                                 │
  │   fixHint: String? (non-null when strategy=RETRY)    │
  └──────────────────────────────────────────────────────┘
              │
         canFix=true?
        /              \
       YES             NO (ABORT)
        │               │
  inject context    write failure_report.json
  repair.hint       PipelineFailed(explanation)
  repair.explanation return outcome
  emit DiagnosticsCompleted
        │
  executeNodeWithRetry(node, repairBudget=1)
        │
   success?
   /       \
  YES      NO
   │        │
  continue  write failure_report.json
  pipeline  PipelineFailed(repairFailReason)


New files:
  src/main/kotlin/attractor/engine/FailureDiagnoser.kt
    ├── interface FailureDiagnoser { fun analyze(...): FailureAnalysis }
    ├── data class FailureAnalysis(strategy, canFix, explanation, fixHint)
    ├── enum class FixStrategy { RETRY_WITH_HINT, SKIP, ABORT }
    ├── class LlmFailureDiagnoser(client: Client) : FailureDiagnoser
    └── object NullDiagnoser : FailureDiagnoser  (returns ABORT instantly)

Modified files:
  Engine.kt           — runLoop() post-failure integration point
  EngineConfig.kt     — add diagnoser: FailureDiagnoser = NullDiagnoser
  PipelineEvent.kt    — DiagnosticsStarted, DiagnosticsCompleted events
  PipelineState.kt    — handle new events (log messages + stage status)
  PipelineRunner.kt   — create LlmFailureDiagnoser and pass to EngineConfig
```

### FailureAnalysis Data Model

```kotlin
enum class FixStrategy { RETRY_WITH_HINT, SKIP, ABORT }

data class FailureAnalysis(
    val strategy: FixStrategy,
    val canFix: Boolean,          // true iff strategy != ABORT
    val explanation: String,      // human-readable reason + what the diagnoser decided
    val fixHint: String? = null   // non-null only when strategy == RETRY_WITH_HINT
)
```

### LlmFailureDiagnoser Prompt

The diagnoser calls the LLM with a structured JSON-mode prompt. System prompt:

```
You are a pipeline failure analyst. Given information about a failed pipeline stage, classify
the failure and decide if an automatic repair is possible.

Respond ONLY with a valid JSON object:
{
  "strategy": "RETRY_WITH_HINT" | "SKIP" | "ABORT",
  "explanation": "...",
  "fixHint": "..." or null
}

RETRY_WITH_HINT: the failure is likely transient or fixable with better guidance; provide a
  concrete fixHint that will be injected as repair context.
SKIP: the stage can be safely skipped (treat as PARTIAL_SUCCESS).
ABORT: the failure is deterministic or unrecoverable; no repair is possible.

fixHint must be concise (≤ 300 chars) and actionable for the LLM executing the stage.
```

User prompt includes:
- Node ID and label
- Failure reason (truncated to 1000 chars)
- Last 20 lines of `logsRoot/<nodeId>/live.log` (if exists)
- Node's original prompt (truncated to 500 chars)

### failure_report.json Format

Written to `logsRoot/failure_report.json` on any terminal failure (ABORT or repair failure):

```json
{
  "failedNode": "build_output",
  "failureReason": "...",
  "diagnosisStrategy": "ABORT",
  "diagnosisExplanation": "...",
  "repairAttempted": false,
  "repairFailureReason": null,
  "timestamp": "2026-02-26T10:00:00Z"
}
```

### Repair Attempt Context Injection

When `strategy == RETRY_WITH_HINT`:
```
context.set("repair.hint", analysis.fixHint)
context.set("repair.explanation", analysis.explanation)
context.set("repair.attempt", "1")
```

The node's prompt can reference `$repair.hint` and `$repair.explanation` directly. Nodes that
don't reference these vars still benefit from the retry itself.

### New PipelineEvents

```kotlin
data class DiagnosticsStarted(val stageName: String, val stageIndex: Int) : PipelineEvent()
data class DiagnosticsCompleted(
    val stageName: String,
    val stageIndex: Int,
    val canFix: Boolean,
    val strategy: String,
    val explanation: String
) : PipelineEvent()
data class RepairAttempted(val stageName: String, val stageIndex: Int) : PipelineEvent()
data class RepairSucceeded(val stageName: String, val stageIndex: Int, val durationMs: Long) : PipelineEvent()
data class RepairFailed(val stageName: String, val stageIndex: Int, val reason: String) : PipelineEvent()
```

## Implementation Plan

### Phase 1: FailureDiagnoser service (~20%)

**Files:**
- `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` — Create

**Tasks:**
- [ ] Define `FixStrategy` enum: `RETRY_WITH_HINT`, `SKIP`, `ABORT`
- [ ] Define `FailureAnalysis(strategy, canFix, explanation, fixHint)` data class
- [ ] Define `FailureDiagnoser` interface: `fun analyze(node, outcome, context, graph, logsRoot): FailureAnalysis`
- [ ] Implement `NullDiagnoser` object: always returns `FailureAnalysis(ABORT, false, "no diagnoser configured")`
- [ ] Implement `LlmFailureDiagnoser(client: Client)`:
  - Build user prompt from: `failureReason`, last 20 lines of `live.log`, node label, node prompt (truncated)
  - Call `generate(model = "claude-haiku-4-5-20251001", prompt = userPrompt, system = systemPrompt, maxTokens = 512)`
  - Parse JSON response: try `Json.decodeFromString(responseText)` → `FailureAnalysis`
  - On parse error: return `FailureAnalysis(ABORT, false, "diagnosis failed: ${e.message}")`
  - Wrap entire method in try/catch: any exception → `FailureAnalysis(ABORT, false, "diagnoser exception: ${e.message}")`
- [ ] No new Gradle dependencies — uses `kotlinx.serialization.json` (already present) and `attractor.llm.generate`

### Phase 2: New PipelineEvents (~10%)

**Files:**
- `src/main/kotlin/attractor/events/PipelineEvent.kt` — Modify

**Tasks:**
- [ ] Add `DiagnosticsStarted(stageName: String, stageIndex: Int) : PipelineEvent()`
- [ ] Add `DiagnosticsCompleted(stageName: String, stageIndex: Int, canFix: Boolean, strategy: String, explanation: String) : PipelineEvent()`
- [ ] Add `RepairAttempted(stageName: String, stageIndex: Int) : PipelineEvent()`
- [ ] Add `RepairSucceeded(stageName: String, stageIndex: Int, durationMs: Long) : PipelineEvent()`
- [ ] Add `RepairFailed(stageName: String, stageIndex: Int, reason: String) : PipelineEvent()`

### Phase 3: PipelineState handles new events (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt` — Modify

**Tasks:**
- [ ] In `update(event)` when block: handle `DiagnosticsStarted`:
  - Update stage record with name matching `event.stageName` to `status = "diagnosing"`
  - `log("[${event.stageIndex}] 🔍 Diagnosing failure: ${event.stageName}")`
- [ ] Handle `DiagnosticsCompleted`:
  - `log("[${event.stageIndex}] 📋 Diagnosis: ${if (event.canFix) "fixable" else "unrecoverable"} — ${event.strategy}: ${event.explanation.take(120)}")`
- [ ] Handle `RepairAttempted`:
  - Update stage record to `status = "repairing"`
  - `log("[${event.stageIndex}] 🔧 Repair attempt: ${event.stageName}")`
- [ ] Handle `RepairSucceeded`:
  - Update stage record to `status = "completed"` with `durationMs`
  - `log("[${event.stageIndex}] ✓ Repair succeeded: ${event.stageName} (${event.durationMs}ms)")`
- [ ] Handle `RepairFailed`:
  - Update stage record to `status = "failed"` (already failed; just update error field)
  - `log("[${event.stageIndex}] ✗ Repair failed: ${event.stageName}: ${event.reason}")`
- [ ] Add `"diagnosing"` and `"repairing"` to the `StageRecord.status` documented values in a comment (existing values: running, completed, failed, retrying)

### Phase 4: Engine integration (~35%)

**Files:**
- `src/main/kotlin/attractor/engine/Engine.kt` — Modify
- `src/main/kotlin/attractor/engine/EngineConfig.kt` (inline in Engine.kt) — Modify

**Tasks:**
- [ ] Add `diagnoser: FailureDiagnoser = NullDiagnoser` to `EngineConfig` data class
- [ ] In `runLoop()`, locate the "no retry target → halt" block (lines ~202–214):
  ```kotlin
  // BEFORE (existing):
  eventBus.emit(PipelineEvent.PipelineFailed(outcome.failureReason, elapsed()))
  return outcome

  // AFTER: insert diagnosis + repair before the final halt
  ```
- [ ] Replace the terminal `PipelineFailed` call (when `nextEdge == null && FAIL && no retryTarget`) with:
  ```kotlin
  val diagnosis = diagnoseStageFail(node, outcome, context, graph, stageIndex, logsRoot)
  if (diagnosis.canFix && diagnosis.strategy == FixStrategy.RETRY_WITH_HINT) {
      // Inject repair context
      context.set("repair.hint", diagnosis.fixHint ?: "")
      context.set("repair.explanation", diagnosis.explanation)
      context.set("repair.attempt", "1")
      // Single repair attempt (budget = 1)
      eventBus.emit(RepairAttempted(node.label, stageIndex))
      val repairOutcome = executeNodeWithRetry(node, context, graph, stageIndex, maxAttempts = 1)
      if (repairOutcome.status.isSuccess) {
          eventBus.emit(RepairSucceeded(node.label, stageIndex, elapsed()))
          completedNodes.add(node.id)
          nodeOutcomes[node.id] = repairOutcome
          nodeDurations[node.id] = 0L
          context.applyUpdates(repairOutcome.contextUpdates)
          context.set("outcome", repairOutcome.status.toString())
          val checkpoint = Checkpoint.create(context, node.id, completedNodes.toList(), nodeDurations.toMap())
          checkpoint.save(config.logsRoot)
          eventBus.emit(CheckpointSaved(node.id))
          // Select next edge from repair outcome
          val repairEdge = EdgeSelector.select(node, repairOutcome, context, graph)
          if (repairEdge == null) { break } else { currentNodeId = repairEdge.to; stageIndex++; continue }
      } else {
          eventBus.emit(RepairFailed(node.label, stageIndex, repairOutcome.failureReason))
          writeFailureReport(node, repairOutcome, diagnosis, logsRoot, repairAttempted = true)
          eventBus.emit(PipelineEvent.PipelineFailed("Repair failed: ${repairOutcome.failureReason}", elapsed()))
          return repairOutcome
      }
  } else if (diagnosis.strategy == FixStrategy.SKIP) {
      // Treat as PARTIAL_SUCCESS
      val skipOutcome = Outcome.partial(notes = "Skipped by diagnoser: ${diagnosis.explanation}")
      completedNodes.add(node.id)
      nodeOutcomes[node.id] = skipOutcome
      nodeDurations[node.id] = 0L
      val checkpoint = Checkpoint.create(context, node.id, completedNodes.toList(), nodeDurations.toMap())
      checkpoint.save(config.logsRoot)
      val skipEdge = EdgeSelector.select(node, skipOutcome, context, graph)
      if (skipEdge == null) { break } else { currentNodeId = skipEdge.to; stageIndex++; continue }
  } else {
      // ABORT
      writeFailureReport(node, outcome, diagnosis, logsRoot, repairAttempted = false)
      eventBus.emit(PipelineEvent.PipelineFailed(diagnosis.explanation.ifBlank { outcome.failureReason }, elapsed()))
      return outcome
  }
  ```
- [ ] Add private `fun diagnoseStageFail(node, outcome, context, graph, stageIndex, logsRoot): FailureAnalysis`:
  - If `node.attrs["failure_diagnosis_disabled"]?.asString() == "true"` → return `NullDiagnoser.analyze(...)` immediately
  - Emit `DiagnosticsStarted(node.label, stageIndex)`
  - Call `config.diagnoser.analyze(node, outcome, context, graph, logsRoot)`
  - Emit `DiagnosticsCompleted(node.label, stageIndex, analysis.canFix, analysis.strategy.name, analysis.explanation)`
  - Return analysis
- [ ] Add private `fun writeFailureReport(node, outcome, analysis, logsRoot, repairAttempted)`:
  - Build JSON object with `failedNode`, `failureReason`, `diagnosisStrategy`, `diagnosisExplanation`,
    `repairAttempted`, `repairFailureReason`, `timestamp`
  - Write to `File(logsRoot, "failure_report.json")`
  - Wrap in try/catch to not disrupt shutdown path
- [ ] Note: `executeNodeWithRetry()` needs an optional `maxAttempts` override parameter, or extract
  a `executeNodeOnce()` helper for the single repair attempt. Prefer: add `maxAttemptsOverride: Int? = null`
  param to `executeNodeWithRetry` — when non-null, use it instead of `retryPolicy.maxAttempts`.

### Phase 5: PipelineRunner wiring (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — Modify

**Tasks:**
- [ ] In `runPipeline()`, after `val backend = ...`:
  ```kotlin
  val diagnoser = if (options.simulate) {
      NullDiagnoser
  } else {
      if (hasKey) LlmFailureDiagnoser(Client.fromEnv()) else NullDiagnoser
  }
  ```
  (Note: reuse the `hasKey` boolean computed for `backend`)
- [ ] Pass `diagnoser` to `EngineConfig`:
  ```kotlin
  val config = EngineConfig(
      ...,
      diagnoser = diagnoser
  )
  ```
- [ ] Import `FailureDiagnoser`, `LlmFailureDiagnoser`, `NullDiagnoser` from `attractor.engine`

### Phase 6: Web UI — "Diagnosing…" and repair status (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (embedded CSS/JS)

**CSS additions:**
- [ ] `.stage.diagnosing .stage-status-icon` — amber hourglass: `content: "⏳"; color: #e3b341;`
- [ ] `.stage.repairing .stage-status-icon` — wrench: `content: "🔧"; color: #58a6ff;`
- [ ] Add `diagnosing` and `repairing` to the stage status icon switch in `buildStageRow()` JS function

**JS changes (in `buildStageRow()` or its equivalent):**
- [ ] Handle `stage.status === "diagnosing"`: show "Diagnosing…" label + hourglass icon
- [ ] Handle `stage.status === "repairing"`: show "Repairing…" label + wrench icon
- [ ] `failure_report.json` is a known artifact path → if stage status is "failed" and
  `File(logsRoot, "failure_report.json").exists()`, `updatePanel()` adds a "View Failure Report"
  link that opens the artifact in the Artifact modal (from Sprint 005)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` | Create | `FixStrategy`, `FailureAnalysis`, `FailureDiagnoser` interface, `NullDiagnoser`, `LlmFailureDiagnoser` |
| `src/main/kotlin/attractor/events/PipelineEvent.kt` | Modify | Add 5 new event types: `DiagnosticsStarted/Completed`, `RepairAttempted/Succeeded/Failed` |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | Handle new events; add "diagnosing"/"repairing" stage status values |
| `src/main/kotlin/attractor/engine/Engine.kt` | Modify | `EngineConfig.diagnoser`; `diagnoseStageFail()`; repair branch; `writeFailureReport()`; `executeNodeWithRetry` override |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Create `LlmFailureDiagnoser`, pass to `EngineConfig` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Stage status CSS/JS for "diagnosing"/"repairing"; failure report link |

## Definition of Done

- [ ] `FailureDiagnoser` interface + `NullDiagnoser` + `LlmFailureDiagnoser` exist in `attractor.engine`
- [ ] `NullDiagnoser` always returns `FixStrategy.ABORT` — pipeline behavior unchanged when no API key
- [ ] `LlmFailureDiagnoser` calls Claude Haiku for diagnosis; parses JSON response to `FailureAnalysis`
- [ ] `LlmFailureDiagnoser` wraps entire call in try/catch — any exception falls back to `ABORT`
- [ ] `EngineConfig` has `diagnoser: FailureDiagnoser = NullDiagnoser`
- [ ] After stage failure with no `retryTarget`: `diagnoseStageFail()` is called; `DiagnosticsStarted` and `DiagnosticsCompleted` events emitted
- [ ] `failure_diagnosis_disabled=true` node attribute bypasses diagnosis completely
- [ ] Strategy `RETRY_WITH_HINT`: `repair.hint`, `repair.explanation`, `repair.attempt` injected into context; single repair attempt executed; `RepairAttempted` event emitted
- [ ] Successful repair: `RepairSucceeded` event; pipeline continues; stage status in UI shows "completed"
- [ ] Failed repair or `ABORT`: `failure_report.json` written to `logsRoot/`; `PipelineFailed` event; pipeline halts
- [ ] Strategy `SKIP`: stage treated as `PARTIAL_SUCCESS`; pipeline continues
- [ ] `failure_report.json` contains: `failedNode`, `failureReason`, `diagnosisStrategy`, `diagnosisExplanation`, `repairAttempted`, `repairFailureReason`, `timestamp`
- [ ] `PipelineState` shows "Diagnosing…" / "Repairing…" stage status in web UI
- [ ] Simulation mode: `NullDiagnoser` used; `failure_report.json` written with `diagnosisExplanation: "simulation mode"`
- [ ] No API key: `NullDiagnoser` used; same behavior as simulation
- [ ] Existing retry/routing (`retryTarget`, `fallbackRetryTarget`, `goalGate`) unchanged
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: all existing flows (pause, resume, cancel, iterate, version history, navigation)

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| LLM diagnostic call fails (network, timeout, parse error) | Medium | Medium | Full try/catch; any exception → `ABORT` fallback, same behavior as today |
| Repair attempt creates infinite loop (always retryable, always fails) | N/A | N/A | Repair budget is exactly 1 attempt; no loop possible |
| `repair.hint` context injection alters unrelated stages | Low | Low | Variables are only consumed if the node prompt references `$repair.hint`; other nodes unaffected |
| Diagnostic LLM call is slow, blocking pipeline thread | Low | Medium | Haiku model is fast (~1-2s); call is on the pipeline thread (same as existing LLM calls) |
| `EngineConfig` default change (`diagnoser = NullDiagnoser`) breaks existing tests | Low | Low | `NullDiagnoser.analyze()` always returns `ABORT` immediately — same behavior as current code which has no diagnoser |
| `executeNodeWithRetry` override parameter misuse | Low | Low | Override only used in repair path (maxAttempts=1); normal path unchanged |
| Stage record status "diagnosing" not handled in UI switch statement | Low | Low | Explicit DoD check; add to stage status icon map before ship |
| `failure_report.json` write fails (disk full, permissions) | Very Low | Low | Wrapped in try/catch; log warning; pipeline halts without the report |

## Security Considerations

- Diagnostic LLM prompt includes `failureReason` (from handler output) and `live.log` content
  — both are already stored in logs and visible in the web UI; no new exposure
- `failure_report.json` is written to `logsRoot/` (same access control as existing log files)
- LLM response is parsed as JSON only — no eval or code execution from response
- `repair.hint` is injected as a plain context string, not executed; security profile same as
  existing context variable expansion

## Dependencies

- Sprint 001 (completed) — `PipelineEvent` / `PipelineEventBus` system used for new events
- Sprint 005 (completed) — Artifact modal can render `failure_report.json` inline (text file)
- Sprint 006 (completed) — No functional dependency; repair retries are independent of view-only gating

## Open Questions

1. Should `SKIP` strategy be supported? It risks silently advancing a broken pipeline. Consider
   removing it from the initial implementation (only `RETRY_WITH_HINT` and `ABORT`).
2. Should the diagnostic model be configurable via DOT attribute `failure_diagnosis_model=...`?
   Default to Haiku for speed and cost; allow per-node override in a follow-up sprint.
3. Should `DiagnosticsCompleted` carry `fixHint` for the UI to display? Currently it only carries
   `explanation`. The hint is implementation detail — probably fine to omit from the event.
4. Should the repair attempt write its own stage log to a `_repair` subdirectory of the original
   stage dir (e.g., `logsRoot/<nodeId>_repair/live.log`)?
