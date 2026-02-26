# Sprint 007: Intelligent Failure Diagnosis and Self-Healing

## Overview

When a pipeline stage exhausts its retry budget and there is no `retry_target` or
`fallback_retry_target` routing configured, the engine emits `PipelineFailed` with the raw
exception string and halts. The failure message is whatever the handler threw — an HTTP status
code, a truncated LLM response, or a Java exception — with no deeper understanding of _why_ it
failed or whether the situation is auto-recoverable.

This sprint adds an **intelligent failure diagnosis layer** that activates only after all existing
retry and routing mechanisms are exhausted. A new `FailureDiagnoser` service accepts a
`FailureContext` bundle (node info, failure reason, stage log artifacts) and uses a lightweight LLM
call to classify the failure and produce a `DiagnosisResult` with one of three strategies:
`RETRY_WITH_HINT`, `SKIP`, or `ABORT`.

For `RETRY_WITH_HINT`, the engine injects the repair hint (`repair.hint`, `repair.explanation`,
`repair.attempt`) into the pipeline context and performs a single repair attempt on the same stage
using a separate `_repair` log subdirectory. For `SKIP`, the stage is treated as `PARTIAL_SUCCESS`
and the pipeline continues (only available when the node declares
`failure_diagnosis_allow_skip=true`). For `ABORT`, the engine writes a structured
`failure_report.json` to the run's `logsRoot` and halts with an enriched error message.

The mechanism degrades gracefully: in simulation mode or when no API key is present, a
`NullFailureDiagnoser` is used that immediately returns `ABORT`, writing a basic failure report.
This is strictly equivalent to existing behavior, with the addition of a report file.

## Use Cases

1. **Transient LLM error recovery**: A `codergen` stage fails because the Anthropic API returned
   a 529 (overloaded). Normal retries also hit rate limits. The diagnoser classifies it as
   transient, injects `repair.hint = "API was overloaded; use a simpler, shorter prompt"` into
   context, and the repair attempt succeeds on a fresh call.

2. **Malformed output self-correction**: A stage produces output that fails downstream validation.
   The diagnoser reads the `live.log` and `response.md` artifacts, identifies the format issue,
   and injects a repair hint with the expected output format. The stage retries with corrected
   guidance and succeeds.

3. **Unrecoverable failure — structured report**: A stage tries to run a command on a path that
   doesn't exist. The diagnoser classifies this as `ABORT` (deterministic, no hint can fix a
   missing file). A `failure_report.json` is written to `logsRoot/` with the full analysis and
   the pipeline halts with a clear, human-readable explanation.

4. **Non-critical stage skip**: A stage annotated with `failure_diagnosis_allow_skip=true`
   produces a non-essential artifact that fails. The diagnoser returns `SKIP` and the pipeline
   continues to the next stage. Without the node attribute, `SKIP` is never returned.

5. **No API key / simulation mode**: `NullFailureDiagnoser` is wired. After stage failure, basic
   `failure_report.json` is written (`diagnosisExplanation: "no LLM configured"`) and the
   pipeline halts exactly as it does today.

## Architecture

```
Engine.runLoop()
  └── executeNodeWithRetry(node, ...)          [existing — retries per max_retries]
        └── [all retries exhausted → FAIL]
              │
  [EdgeSelector.select() == null + FAIL]       [existing]
  [retryTarget/fallbackRetryTarget routing]     [existing, checked first]
  [No route found]                              ← NEW integration point
              │
              ▼
  diagnoseStageFail(node, outcome, context, graph, stageIndex, logsRoot)
    ├── emit DiagnosticsStarted(nodeId, stageName, stageIndex)
    ├── config.diagnoser.analyze(FailureContext {...})  → DiagnosisResult
    └── emit DiagnosticsCompleted(nodeId, stageName, stageIndex, recoverable, strategy, explanation)
              │
     strategy?
    /    |     \
RETRY  SKIP   ABORT
  │      │      │
inject  mark  write
ctx    PARTIAL failure_report.json
hint   SUCCESS PipelineFailed
  │      │     return
run     select
repair  next
attempt  edge
  │
success?
 / \
YES  NO
│    │
cont write failure_report.json
     RepairFailed
     PipelineFailed
     return


New files:
  src/main/kotlin/attractor/engine/FailureDiagnoser.kt
    ├── data class FailureContext(nodeId, stageName, stageIndex, failureReason, logsRoot, contextSnapshot)
    ├── data class DiagnosisResult(recoverable, strategy, explanation, repairHint)
    ├── enum class FixStrategy { RETRY_WITH_HINT, SKIP, ABORT }
    ├── fun interface FailureDiagnoser { fun analyze(ctx: FailureContext): DiagnosisResult }
    ├── object NullFailureDiagnoser : FailureDiagnoser
    └── class LlmFailureDiagnoser(client: Client, model: String) : FailureDiagnoser

Modified files:
  Engine.kt         — EngineConfig.diagnoser; diagnoseStageFail(); repair branch; writeFailureReport()
  PipelineEvent.kt  — DiagnosticsStarted/Completed; RepairAttempted/Succeeded/Failed
  PipelineState.kt  — handle new events; "diagnosing"/"repairing" stage statuses; hasFailureReport flag
  PipelineRunner.kt — create LlmFailureDiagnoser; pass to EngineConfig
  WebMonitorServer.kt — stage status CSS/JS; hasFailureReport link
```

### FailureContext and DiagnosisResult

```kotlin
data class FailureContext(
    val nodeId: String,
    val stageName: String,
    val stageIndex: Int,
    val failureReason: String,
    val logsRoot: String,
    val contextSnapshot: Map<String, Any>
)

enum class FixStrategy { RETRY_WITH_HINT, SKIP, ABORT }

@Serializable
data class DiagnosisResult(
    val recoverable: Boolean,
    val strategy: String,       // FixStrategy name
    val explanation: String,
    val repairHint: String? = null
)
```

### LlmFailureDiagnoser: Input Construction

The diagnoser reads stage artifacts (not the raw `node.prompt`, which is pre-expansion):

```
[Diagnostic inputs]
  failureReason    — Outcome.failureReason, truncated to 1000 chars
  prompt.md        — logsRoot/<nodeId>/prompt.md, last 500 chars (the actual expanded prompt)
  response.md      — logsRoot/<nodeId>/response.md, last 500 chars (what the LLM returned)
  live.log         — logsRoot/<nodeId>/live.log, last 30 lines
  status.json      — logsRoot/<nodeId>/status.json, verbatim (if exists)
```

### LlmFailureDiagnoser: System Prompt

```
You are a pipeline failure analyst. Given information about a failed pipeline stage,
classify the failure and decide if an automatic repair is possible.

Respond ONLY with a valid JSON object with these fields:
{
  "recoverable": true/false,
  "strategy": "RETRY_WITH_HINT" | "SKIP" | "ABORT",
  "explanation": "...",
  "repairHint": "..." or null
}

RETRY_WITH_HINT: transient or fixable; provide a concise repairHint (≤ 300 chars).
SKIP: stage can be safely skipped (only emit this if the user has explicitly allowed it).
ABORT: deterministic or unrecoverable; no repair is possible.
```

### failure_report.json Format

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

Written to `logsRoot/failure_report.json` on any terminal failure.

### PipelineState: hasFailureReport Flag

`PipelineState` gains a `hasFailureReport: AtomicBoolean = AtomicBoolean(false)` field set when
the engine calls `writeFailureReport()`. The `toJson()` serializer includes
`"hasFailureReport":${hasFailureReport.get()}` so the browser can render a direct link.

### New PipelineEvents

```kotlin
data class DiagnosticsStarted(val nodeId: String, val stageName: String, val stageIndex: Int) : PipelineEvent()
data class DiagnosticsCompleted(
    val nodeId: String, val stageName: String, val stageIndex: Int,
    val recoverable: Boolean, val strategy: String, val explanation: String
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
- [ ] Define `FailureContext(nodeId, stageName, stageIndex, failureReason, logsRoot, contextSnapshot)`
- [ ] Define `@Serializable data class DiagnosisResult(recoverable, strategy, explanation, repairHint?)`
- [ ] Define `fun interface FailureDiagnoser { fun analyze(ctx: FailureContext): DiagnosisResult }`
- [ ] Implement `NullFailureDiagnoser` object:
  - Always returns `DiagnosisResult(recoverable=false, strategy="ABORT", explanation="no LLM diagnoser configured")`
- [ ] Implement `class LlmFailureDiagnoser(val client: Client, val model: String = LlmCodergenBackend.DEFAULT_MODEL)`:
  - `buildInput(ctx)`: read artifacts from `logsRoot/<nodeId>/` (prompt.md, response.md, live.log,
    status.json), truncate to limits; include `failureReason`
  - `analyze(ctx)`: call `generate(model, prompt = userPrompt, system = SYSTEM_PROMPT, maxTokens = 512)`
  - Parse response: `Json.decodeFromString<DiagnosisResult>(responseText.trim())`
    — on `JsonDecodingException`: return `DiagnosisResult(false, "ABORT", "diagnosis parse error: ${e.message}")`
  - Wrap entire method in try/catch(Exception): return `DiagnosisResult(false, "ABORT", "diagnoser exception: ${e.message}")`
  - `SKIP` guard: if `ctx.contextSnapshot["failure_diagnosis_allow_skip"] != "true"` and result strategy is `SKIP`,
    downgrade to `ABORT` with explanation `"SKIP not permitted for this node"`
- [ ] No new Gradle dependencies — uses `kotlinx.serialization.json` (existing) and `attractor.llm.generate`

### Phase 2: New PipelineEvents (~10%)

**Files:**
- `src/main/kotlin/attractor/events/PipelineEvent.kt` — Modify

**Tasks:**
- [ ] Add `DiagnosticsStarted(nodeId: String, stageName: String, stageIndex: Int)`
- [ ] Add `DiagnosticsCompleted(nodeId: String, stageName: String, stageIndex: Int, recoverable: Boolean, strategy: String, explanation: String)`
- [ ] Add `RepairAttempted(stageName: String, stageIndex: Int)`
- [ ] Add `RepairSucceeded(stageName: String, stageIndex: Int, durationMs: Long)`
- [ ] Add `RepairFailed(stageName: String, stageIndex: Int, reason: String)`

### Phase 3: PipelineState handles new events and `hasFailureReport` (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineState.kt` — Modify

**Tasks:**
- [ ] Add `val hasFailureReport = AtomicBoolean(false)` field
- [ ] Add `"diagnosing"` and `"repairing"` to `StageRecord.status` documented comment
- [ ] In `update(event)`:
  - `DiagnosticsStarted`: find stage record by `event.nodeId` and `stageIndex`; set to `status="diagnosing"`;
    log `"[${event.stageIndex}] 🔍 Diagnosing failure: ${event.stageName}"`
  - `DiagnosticsCompleted`: log `"[${event.stageIndex}] 📋 Diagnosis: ${if (event.recoverable) "fixable" else "unrecoverable"} — ${event.strategy}: ${event.explanation.take(120)}"`
  - `RepairAttempted`: find by stageName/index; set to `status="repairing"`;
    log `"[${event.stageIndex}] 🔧 Repair attempt: ${event.stageName}"`
  - `RepairSucceeded`: find by stageName/index; set to `status="completed"`, `durationMs=event.durationMs`;
    log `"[${event.stageIndex}] ✓ Repair succeeded: ${event.stageName} (${event.durationMs}ms)"`
  - `RepairFailed`: find by stageName/index; set `status="failed"`, `error=event.reason`;
    log `"[${event.stageIndex}] ✗ Repair failed: ${event.stageName}: ${event.reason}"`
- [ ] In `reset()`: add `hasFailureReport.set(false)`
- [ ] In `toJson(logsRoot)`: add `"hasFailureReport":${hasFailureReport.get()},` to the JSON output

### Phase 4: Engine integration (~30%)

**Files:**
- `src/main/kotlin/attractor/engine/Engine.kt` — Modify

**Tasks:**
- [ ] Add `diagnoser: FailureDiagnoser = NullFailureDiagnoser` to `EngineConfig` data class
- [ ] Add private `fun diagnoseStageFail(node, outcome, context, graph, stageIndex, logsRoot): DiagnosisResult`:
  - If `node.attrs["failure_diagnosis_disabled"]?.asString() == "true"` → return NullFailureDiagnoser result immediately
  - Build `FailureContext(nodeId=node.id, stageName=node.label, stageIndex, failureReason=outcome.failureReason, logsRoot, contextSnapshot=context.snapshot())`
  - Emit `DiagnosticsStarted(node.id, node.label, stageIndex)`
  - Call `config.diagnoser.analyze(ctx)` → `result`
  - Emit `DiagnosticsCompleted(node.id, node.label, stageIndex, result.recoverable, result.strategy, result.explanation)`
  - Return `result`
- [ ] Add private `fun writeFailureReport(node, outcome, result, logsRoot, repairAttempted, repairFailureReason)`:
  - Build JSON with `buildJsonObject { ... }` (same pattern as `writeManifest`)
  - Write to `File(logsRoot, "failure_report.json")`
  - Set `// Note: state.hasFailureReport is set by PipelineRunner via PipelineEvent`
  - Actually: emit a new lightweight `FailureReportWritten(nodeId)` event, OR update state
    directly. **Simpler**: `writeFailureReport` just writes the file and returns; `PipelineRunner`
    observes `PipelineFailed` and checks file existence, then sets `hasFailureReport`. Wait —
    server-side check is fine. PipelineRunner event handler for `PipelineFailed` can check
    `File(logsRoot, "failure_report.json").exists()` and set `state.hasFailureReport.set(true)`.
  - Wrap write in `try/catch(Exception) { /* log warning; don't disrupt halt */ }`
- [ ] In `runLoop()`, replace the terminal `PipelineFailed + return` in the "no edge + FAIL + no retryTarget" block:
  ```kotlin
  val diagnosis = diagnoseStageFail(node, outcome, context, graph, stageIndex, logsRoot)
  when (FixStrategy.valueOf(diagnosis.strategy)) {
      FixStrategy.RETRY_WITH_HINT -> {
          val hint = diagnosis.repairHint ?: ""
          context.set("repair.hint", hint)
          context.set("repair.explanation", diagnosis.explanation)
          context.set("repair.attempt", "1")
          eventBus.emit(RepairAttempted(node.label, stageIndex))
          val repairLogsRoot = "${logsRoot}/${node.id}_repair"
          val repairOutcome = executeSingleRepairAttempt(node, context, graph, stageIndex, repairLogsRoot)
          if (repairOutcome.status.isSuccess) {
              eventBus.emit(RepairSucceeded(node.label, stageIndex, elapsed()))
              // Continue pipeline normally
              completedNodes.add(node.id); nodeOutcomes[node.id] = repairOutcome
              nodeDurations[node.id] = 0L
              context.applyUpdates(repairOutcome.contextUpdates)
              context.set("outcome", repairOutcome.status.toString())
              val ckpt = Checkpoint.create(context, node.id, completedNodes.toList(), nodeDurations.toMap())
              ckpt.save(config.logsRoot)
              eventBus.emit(CheckpointSaved(node.id))
              val repairEdge = EdgeSelector.select(node, repairOutcome, context, graph)
              if (repairEdge == null) break
              else { currentNodeId = repairEdge.to; stageIndex++; continue }
          } else {
              eventBus.emit(RepairFailed(node.label, stageIndex, repairOutcome.failureReason))
              writeFailureReport(node, repairOutcome, diagnosis, config.logsRoot, repairAttempted=true, repairOutcome.failureReason)
              eventBus.emit(PipelineEvent.PipelineFailed("Repair failed: ${repairOutcome.failureReason}", elapsed()))
              return repairOutcome
          }
      }
      FixStrategy.SKIP -> {
          val skipOutcome = Outcome.partial(notes = "Skipped by diagnoser: ${diagnosis.explanation}")
          completedNodes.add(node.id); nodeOutcomes[node.id] = skipOutcome; nodeDurations[node.id] = 0L
          val ckpt = Checkpoint.create(context, node.id, completedNodes.toList(), nodeDurations.toMap())
          ckpt.save(config.logsRoot)
          val skipEdge = EdgeSelector.select(node, skipOutcome, context, graph)
          if (skipEdge == null) break
          else { currentNodeId = skipEdge.to; stageIndex++; continue }
      }
      FixStrategy.ABORT -> {
          writeFailureReport(node, outcome, diagnosis, config.logsRoot, repairAttempted=false, null)
          eventBus.emit(PipelineEvent.PipelineFailed(diagnosis.explanation.ifBlank { outcome.failureReason }, elapsed()))
          return outcome
      }
  }
  ```
- [ ] Add private `fun executeSingleRepairAttempt(node, context, graph, stageIndex, repairLogsRoot): Outcome`:
  - Creates the repair log dir: `File(repairLogsRoot).mkdirs()`
  - Calls `registry.resolve(node)` then `handler.execute(node, context, graph, repairLogsRoot)`
  - Honors node timeout (`node.timeoutMillis`) same as `executeNodeWithRetry`
  - Wraps in try/catch: exception → `Outcome.fail(e.message ?: "repair exception")`
  - Does NOT recurse into diagnosis (no diagnoser call from repair path)

### Phase 5: PipelineRunner wiring (~10%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — Modify

**Tasks:**
- [ ] In `runPipeline()`, after `val backend = ...`:
  ```kotlin
  val diagnoser: FailureDiagnoser = when {
      options.simulate -> NullFailureDiagnoser
      hasKey           -> LlmFailureDiagnoser(Client.fromEnv())
      else             -> NullFailureDiagnoser
  }
  ```
- [ ] Pass to `EngineConfig`: `diagnoser = diagnoser`
- [ ] In the event subscriber `when` block, add handling for `PipelineFailed`:
  ```kotlin
  is PipelineEvent.PipelineFailed -> {
      // existing: updateStatus, updateLog, updateFinishedAt ...
      // NEW: check for failure_report.json
      val lr = registry.get(id)?.logsRoot ?: ""
      if (lr.isNotBlank() && java.io.File(lr, "failure_report.json").exists()) {
          state.hasFailureReport.set(true)
      }
  }
  ```
- [ ] Import: `FailureDiagnoser`, `NullFailureDiagnoser`, `LlmFailureDiagnoser` from `attractor.engine`

### Phase 6: Web UI — stage status and failure report link (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (embedded CSS/JS)

**CSS additions:**
- [ ] Stage icon/color for `status === "diagnosing"`:
  `"⏳"` icon; color `#e3b341` (amber) — consistent with existing retrying style
- [ ] Stage icon/color for `status === "repairing"`:
  `"🔧"` icon; color `#58a6ff` (blue) — consistent with accent color

**JS changes:**
- [ ] In stage row rendering (wherever `status === "running"` icon/label is set):
  - Add `case "diagnosing": icon = "⏳"; label = "Diagnosing…"; break;`
  - Add `case "repairing": icon = "🔧"; label = "Repairing…"; break;`
- [ ] In `updatePanel(id)` (or wherever the pipeline detail panel is rendered):
  - Check `p.state.hasFailureReport`:
    ```javascript
    if (p.state.hasFailureReport) {
        // add/show a "📋 View Failure Report" link/button in the panel
        // onclick: openArtifacts(id, 'Failure Report', 'failure_report.json')
        // OR: use a direct /api/run-artifact-file?id=...&path=failure_report.json link
    }
    ```
  - Use the existing `openArtifacts()` modal from Sprint 005 if available, or a plain link

### Phase 7: Tests (~5%)

**Files:**
- `src/test/kotlin/attractor/engine/EngineTest.kt` — Modify (or create if absent)

**Tasks:**
- [ ] Add a `FailOnFirstCallHandler` test stub: fails the first invocation unless
  `context.getString("repair.hint")` is non-blank; succeeds otherwise
- [ ] Test: dead-end failure triggers `diagnoseStageFail()` → repair attempt → pipeline succeeds
- [ ] Test: `NullFailureDiagnoser` on dead-end failure → `failure_report.json` written →
  pipeline halts with `FAIL` outcome
- [ ] Test: `failure_diagnosis_disabled=true` node attribute → diagnosis skipped →
  pipeline halts with original failure message

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` | Create | `FixStrategy`, `FailureContext`, `DiagnosisResult`, `FailureDiagnoser` interface, `NullFailureDiagnoser`, `LlmFailureDiagnoser` |
| `src/main/kotlin/attractor/events/PipelineEvent.kt` | Modify | Add 5 new events: `DiagnosticsStarted/Completed`, `RepairAttempted/Succeeded/Failed` |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | Handle new events; `hasFailureReport` field; `toJson()` update; `reset()` update |
| `src/main/kotlin/attractor/engine/Engine.kt` | Modify | `EngineConfig.diagnoser`; `diagnoseStageFail()`; repair branch (RETRY/SKIP/ABORT); `executeSingleRepairAttempt()`; `writeFailureReport()` |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Create `LlmFailureDiagnoser`; pass to `EngineConfig`; set `hasFailureReport` on `PipelineFailed` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `"diagnosing"`/`"repairing"` stage CSS/JS; failure report link from `hasFailureReport` |
| `src/test/kotlin/attractor/engine/EngineTest.kt` | Modify | Repair + report coverage |

## Definition of Done

- [ ] `FailureDiagnoser` interface + `NullFailureDiagnoser` + `LlmFailureDiagnoser` exist in `attractor.engine`
- [ ] `NullFailureDiagnoser` always returns `FixStrategy.ABORT` immediately — no behavior change when no API key
- [ ] `LlmFailureDiagnoser` reads stage artifacts (not raw node prompt) as diagnostic input
- [ ] `LlmFailureDiagnoser` uses `LlmCodergenBackend.DEFAULT_MODEL` as default; full try/catch on all paths
- [ ] `EngineConfig` has `diagnoser: FailureDiagnoser = NullFailureDiagnoser`
- [ ] After stage failure with no `retryTarget`: `diagnoseStageFail()` is called; `DiagnosticsStarted` and `DiagnosticsCompleted` events emitted; events carry `nodeId`
- [ ] `failure_diagnosis_disabled=true` node attribute: diagnosis skipped entirely
- [ ] `RETRY_WITH_HINT`: `repair.hint`, `repair.explanation`, `repair.attempt` in context; single repair attempt in `logsRoot/<nodeId>_repair/`; `RepairAttempted` emitted
- [ ] Successful repair: `RepairSucceeded` emitted; stage shows "completed"; pipeline continues
- [ ] Failed repair: `RepairFailed` emitted; `failure_report.json` written; `PipelineFailed`; halt
- [ ] `SKIP` (only when `failure_diagnosis_allow_skip=true` on node): stage treated as `PARTIAL_SUCCESS`; pipeline continues; no failure report
- [ ] `ABORT`: `failure_report.json` written to `logsRoot/`; `PipelineFailed` with explanation; halt
- [ ] `failure_report.json` contains: `failedNode`, `failureReason`, `diagnosisStrategy`, `diagnosisExplanation`, `repairAttempted`, `repairFailureReason`, `timestamp`
- [ ] `PipelineState.hasFailureReport` set to `true` when report is written; serialized in `toJson()`
- [ ] Web monitor: stage shows "⏳ Diagnosing…" and "🔧 Repairing…" statuses
- [ ] Web monitor: "View Failure Report" link/button visible when `hasFailureReport=true`
- [ ] Simulation mode: `NullFailureDiagnoser`; same halt behavior; `failure_report.json` written with explanation `"simulation mode: diagnosis skipped"`
- [ ] Existing retry/routing (`retryTarget`, `fallbackRetryTarget`, `goalGate`) unchanged
- [ ] Engine tests added; `NullFailureDiagnoser` test coverage passes
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies
- [ ] No regressions: pause, resume, cancel, iterate, version history, history navigation

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| LLM diagnostic call fails (network, timeout, parse error) | Medium | Medium | Full try/catch; any exception → `ABORT` + report; identical to today's halt |
| Repair attempt infinite loop | N/A | N/A | Single attempt hardcoded; diagnosis is never called from repair path |
| `repair.hint` context injection affects other stages | Low | Low | Variables consumed only if node prompt explicitly references `$repair.hint`; other nodes unaffected |
| `SKIP` silently advances broken pipeline | Low | Medium | Guarded by `failure_diagnosis_allow_skip=true` node attribute; LLM cannot SKIP without it |
| `EngineConfig` default change breaks existing tests | Low | Low | `NullFailureDiagnoser` immediately returns `ABORT` — behavior identical to today, no diagnosis overhead |
| `executeSingleRepairAttempt` uses wrong `logsRoot` for stage artifacts | Low | Medium | Repair writes to `<nodeId>_repair/` subdir; handler receives this path; original stage artifacts unchanged |
| `hasFailureReport` check uses server-side `File.exists()` | N/A | N/A | Done in `PipelineRunner` event handler (server-side Java), not in browser JS |
| `DiagnosticsStarted` emitted for stage not found in `stages` list | Low | Low | `PipelineState` guards with `indexOfLast { ... } >= 0` — same pattern as existing stage updates |

## Security Considerations

- Diagnostic LLM prompt includes `failureReason` and truncated stage artifacts — same data already
  visible in the web monitor; no new exposure
- `failure_report.json` written to `logsRoot/` — same access model as all other artifacts
- LLM response parsed as JSON only; no eval or code execution from response
- `repair.hint` injected as plain context string; same security profile as existing variable expansion
- `FailureContext.contextSnapshot` must **not** include `ANTHROPIC_API_KEY` or other env secrets —
  `Context.snapshot()` only contains pipeline-set variables, not system env (verified: `Context`
  stores a `ConcurrentHashMap<String, Any>` populated by node outcomes, not `System.getenv()`)
- `executeSingleRepairAttempt` uses `repairLogsRoot = logsRoot/<nodeId>_repair` — stays within
  the run's directory; no path traversal risk beyond what the handler already has

## Dependencies

- Sprint 001 (completed) — `PipelineEvent`/`PipelineEventBus` for new events
- Sprint 003 (completed) — `logsRoot` tracking in registry; stage artifact file layout
- Sprint 005 (completed) — Artifact modal (`openArtifacts()`) reused for failure report link
- No new Sprint 007-specific external dependencies

## Open Questions

1. Should the repair attempt timeout use `node.timeoutMillis` (same as original) or a different
   (shorter) timeout? Current plan: same timeout — conservative, avoids surprises.
2. Should `DiagnosticsCompleted` carry `repairHint` for display in the UI? Currently omitted
   (it's implementation detail). Could be added to the log message if useful.
3. Should a successful repair update the family version history in any way? Not for now — the
   repair attempt is an internal engine recovery, not a user-initiated iterate.
4. Should there be a `EngineConfig.failureDiagnosisEnabled: Boolean = true` global kill switch,
   in addition to the per-node `failure_diagnosis_disabled`? Deferred — `NullFailureDiagnoser`
   serves this purpose via PipelineRunner in simulation mode.
