# Sprint 007: Intelligent Failure Diagnosis & Self-Healing

## Overview

Today, when a stage exhausts its retry budget and there is no failure-routing target (`retry_target` / `fallback_retry_target`), the engine halts with `PipelineFailed` and a shallow error string (often `"Max retries exceeded for 'stage_x'"`). That’s correct-but-blind: we lose the opportunity to distinguish transient failures (rate limits, flaky tool calls, malformed-but-fixable output) from truly unrecoverable ones, and we don’t leave behind an actionable, structured postmortem.

This sprint adds a bounded “intelligent failure layer” that activates only after all existing retry + routing mechanisms are exhausted. A new `FailureDiagnoser` service classifies the failure, produces a structured explanation + strategy, and (when safe) injects a single “repair hint” into `Context` before performing exactly one repair attempt using the same handler as the original stage. If diagnosis says “unrecoverable”, if no LLM API key is present, or if the repair attempt fails, the engine writes `failure_report.json` in the run’s `logsRoot` and halts with enriched info.

Key constraints honored:
- No new Gradle dependencies.
- Simulation mode skips LLM diagnosis.
- Exactly one repair attempt per failed stage.
- Existing `retry_target` / `fallback_retry_target` / goal-gate mechanics remain unchanged.

## Use Cases

1. **Transient LLM/tool failure**: A `tool` node fails due to a temporary network issue. After normal retries are exhausted, the diagnoser classifies it as transient and suggests a repair hint (e.g., “retry once after shorter command / verify prerequisites”). Engine retries once with the hint; pipeline continues if successful.
2. **Malformed output**: A `codergen` stage returns output that causes a downstream parse/validation failure. Diagnoser recommends a minimal repair instruction (e.g., “respond with strict JSON schema X”), injected as `$repair.hint`; one repair attempt is made.
3. **Unrecoverable failure**: A stage fails because a required file is missing and cannot be produced by the pipeline. Diagnoser marks unrecoverable; engine halts and writes `failure_report.json`.
4. **No API key / offline mode**: No provider keys are present. Diagnosis is skipped (Null diagnoser), the pipeline halts immediately, and the report explains that auto-diagnosis was unavailable.
5. **Per-node opt-out**: A sensitive stage sets `failure_diagnosis_disabled=true`; diagnosis is skipped and the engine preserves current behavior.

## Architecture

### Where it Hooks In

The diagnoser runs only in the “dead end” failure case:

```text
executeNodeWithRetry(...) -> Outcome.FAIL
  AND EdgeSelector.select(...) == null
  AND no retry_target / fallback_retry_target routing applies
  => FailureDiagnoser.diagnose(...)
     - unrecoverable -> write report, PipelineFailed, halt
     - recoverable   -> context.set("repair.hint", ...)
                        attempt single repair execution (no normal retries)
                        - success -> continue normal flow
                        - fail    -> write report, PipelineFailed, halt
```

### Core Types (new)

```text
FailureDiagnoser
  diagnose(ctx: FailureContext): DiagnosisResult

FailureContext
  - runId, graphId
  - nodeId, nodeLabel, stageIndex
  - failureReason (Outcome.failureReason)
  - logsRoot (for prompt/response/status/live.log best-effort sampling)
  - contextSnapshot (Context.snapshot())

DiagnosisResult
  - recoverable: Boolean
  - classification: String   (e.g., "transient", "prompt_fix", "unrecoverable")
  - explanation: String
  - strategy: String
  - repairHint: String?      (injected into Context when present)
  - confidence: Double?      (optional)
```

### LLM Protocol (bounded + parseable)

`LlmFailureDiagnoser` uses existing `attractor.llm.generate(...)` and requests a strict JSON object (single response, no tools) so we can:
- parse it deterministically
- store it verbatim in `failure_report.json`
- display a short summary in the web monitor log

If parsing fails, we treat as unrecoverable and produce a report with `classification="diagnoser_parse_error"`.

### UI / Event Visibility

Add two new pipeline events:
- `DiagnosticsStarted(nodeId, stageIndex, ...)`
- `DiagnosticsCompleted(nodeId, stageIndex, recoverable, classification, ...)`

`PipelineState` maps these to:
- a stage status `diagnosing` while the LLM call is running
- log lines that include `explanation` + `strategy` (and whether a repair attempt will run)

`WebMonitorServer` adds styling + an icon for `diagnosing` so the stage list shows “Diagnosing…” during the call.

## Implementation Plan

### Phase 1: Add FailureDiagnoser + report schema (~25%)

**Files:**
- `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` (new)

**Tasks:**
- [ ] Define `FailureContext` + `DiagnosisResult` data classes.
- [ ] Implement `NullFailureDiagnoser` (always unrecoverable; includes reason “no LLM configured”).
- [ ] Implement `LlmFailureDiagnoser` using `Client.fromEnv()` / `generate(...)` with a strict-JSON prompt.
- [ ] Add `FailureReport` schema and JSON writer (use existing `kotlinx.serialization.json` patterns in `Engine.kt`).

### Phase 2: Engine integration + single repair attempt (~35%)

**Files:**
- `src/main/kotlin/attractor/engine/Engine.kt`
- `src/main/kotlin/attractor/engine/EngineConfig.kt` (or `Engine.kt` if `EngineConfig` remains co-located)

**Tasks:**
- [ ] Extend `EngineConfig` with `failureDiagnoser: FailureDiagnoser = NullFailureDiagnoser`.
- [ ] In the dead-end fail path (after retries + no routing), call diagnoser unless:
  - `options.simulate=true` (PipelineRunner wires Null diagnoser)
  - `node.attrBool("failure_diagnosis_disabled") == true`
- [ ] Emit `DiagnosticsStarted` / `DiagnosticsCompleted`.
- [ ] If `DiagnosisResult.recoverable` and `repairHint` present:
  - inject `context.set("repair.hint", repairHint)`
  - run exactly one repair attempt (no retries) using the same handler + timeout rules
  - on success: proceed with normal completion bookkeeping and edge selection
  - on failure: write `failure_report.json`, emit `PipelineFailed`, halt
- [ ] Ensure no infinite loops: never re-enter diagnosis from the repair attempt.

### Phase 3: Wire diagnoser from PipelineRunner (~15%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt`

**Tasks:**
- [ ] Construct a diagnoser once per run:
  - simulation mode -> `NullFailureDiagnoser("simulation: no diagnosis")`
  - no API keys -> `NullFailureDiagnoser("no LLM API key present")`
  - else -> `LlmFailureDiagnoser(Client.fromEnv())`
- [ ] Pass diagnoser into `EngineConfig`.

### Phase 4: Events + UI “Diagnosing…” status (~15%)

**Files:**
- `src/main/kotlin/attractor/events/PipelineEvent.kt`
- `src/main/kotlin/attractor/web/PipelineState.kt`
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `DiagnosticsStarted` / `DiagnosticsCompleted` events.
- [ ] Update `PipelineState.update(...)`:
  - set the relevant stage record to `status="diagnosing"` while diagnostics run
  - log diagnosis summary on completion
- [ ] Update `WebMonitorServer`:
  - add icon + CSS for `.stage.diagnosing`
  - render “Diagnosing…” in stage list and stage detail

### Phase 5: Tests + manual verification (~10%)

**Files:**
- `src/test/kotlin/attractor/engine/EngineTest.kt`

**Tasks:**
- [ ] Add a small test-only handler that fails once unless `context.getString("repair.hint")` is set; verify:
  - dead-end failure triggers diagnosis
  - exactly one repair attempt occurs
  - pipeline succeeds after hint injection
- [ ] Add a test where diagnoser returns unrecoverable; verify `failure_report.json` is written.
- [ ] Manual E2E: run a real pipeline with a forced failure and confirm:
  - UI shows “Diagnosing…”
  - report exists in `logs/<run>/failure_report.json`

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` | Create | Diagnosis interface + implementations + report schema |
| `src/main/kotlin/attractor/engine/Engine.kt` | Modify | Hook diagnosis + single repair attempt into dead-end fail path |
| `src/main/kotlin/attractor/engine/EngineConfig` | Modify | Carry diagnoser into engine (exact file location TBD) |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Instantiate diagnoser based on env + simulate mode |
| `src/main/kotlin/attractor/events/PipelineEvent.kt` | Modify | Add diagnostics lifecycle events |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | Map new events to `diagnosing` + log summaries |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Display diagnosing state + diagnosis summary |
| `src/test/kotlin/attractor/engine/EngineTest.kt` | Modify | Coverage for repair attempt + report generation |

## Definition of Done

- [ ] Dead-end stage failures trigger diagnosis (when enabled) after normal retries/routing exhaustion.
- [ ] Exactly one repair attempt is made at most, and never loops.
- [ ] Simulation mode and missing-API-key mode skip diagnosis cleanly (no crashes).
- [ ] `failure_report.json` is written for unrecoverable failures and for failed repair attempts.
- [ ] Web monitor shows a visible “Diagnosing…” stage status during LLM call and logs the diagnosis result.
- [ ] Unit tests for repair attempt + report writing pass.
- [ ] `./gradlew test` passes.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| LLM output not parseable / inconsistent | Medium | Medium | Strict JSON prompt + defensive parse; treat failures as unrecoverable and report |
| Repair hint causes unintended behavior | Medium | Medium | Single bounded attempt; only context injection (no auto code execution changes) |
| UI state confusion (duplicate stages) | Low | Medium | Reuse same stage record; introduce explicit `diagnosing` status |
| Diagnoser errors crash engine | Low | High | Wrap diagnoser call; on exception, write report and halt without repair |

## Security Considerations

- Avoid leaking secrets into the LLM prompt: diagnosis input should omit environment variables and only include bounded logs + stage artifacts relevant to the failure.
- Keep report contents local to `logsRoot`; do not transmit reports externally.
- Ensure workspace path sampling cannot escape `logsRoot`.

## Dependencies

- Existing LLM client + providers (`attractor.llm.Client`, adapters).
- Sprint 005/006 state persistence already provides stable `logsRoot` + run history for inspection (no new dependencies required).

## Open Questions

1. Should the injected key be `repair.hint`, `repair_hint`, or both (for compatibility with variable expansion patterns)?
2. Do we want a global config flag (e.g., `EngineConfig.failureDiagnosisEnabled`) in addition to per-node `failure_diagnosis_disabled`?
3. Should the UI store diagnosis fields per-stage (structured), or is logging the summary sufficient for v1?
4. Should we emit a distinct event when a repair attempt succeeds (vs. relying on normal `StageCompleted`)?

