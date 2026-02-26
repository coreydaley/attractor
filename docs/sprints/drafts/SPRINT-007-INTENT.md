# Sprint 007 Intent: Intelligent Failure Diagnosis and Self-Healing

## Seed

if a stage fails, the pipeline run should stop and the system should try to figure out what went wrong, decide if it can fix it or not, and then fix the issue and rerun or just stop and log the error

## Context

Currently, when a stage exhausts its retry budget and there is no `retryTarget` configured on the
node or graph, the engine emits `PipelineFailed` and halts. The failure reason is a raw string
from the handler (e.g., `"Max retries exceeded for 'stage_build'"`) with no deeper analysis.

The seed asks for an intelligent layer that:
1. **Diagnoses** what actually went wrong (not just the raw exception string)
2. **Decides** if the failure is auto-recoverable
3. **Repairs and reruns** if so — or writes a structured failure report and halts if not

This sprint introduces a `FailureDiagnoser` service that hooks into the Engine after all normal
retry and routing mechanisms have been exhausted. It uses an LLM call to classify the failure,
suggest a fix strategy, and optionally inject a "repair hint" into context before a single repair
attempt. If the repair attempt also fails, or if the failure is classified as unrecoverable, the
engine writes `failure_report.json` and halts with enriched error information.

## Recent Sprint Context

- **Sprint 004** — Dashboard tab (web UI, CSS/JS only)
- **Sprint 005** — Pipeline version history: immutable iterate, `pipeline_family_id`, version history accordion, artifact browser
- **Sprint 006** — History navigation: [View] button, on-demand DB hydration, version navigator strip, `isHydratedViewOnly` action gating

Sprints 004–006 were all web UI features. Sprint 007 returns to the core execution engine for
the first time since the initial build.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/engine/Engine.kt` — Core execution loop. Integration point: after
  `executeNodeWithRetry` returns `FAIL` and no `retryTarget` is found (Engine.kt:202–214).
- `src/main/kotlin/attractor/engine/RetryPolicy.kt` — Existing retry/backoff. The repair attempt
  uses a separate single-shot budget, independent of `max_retries`.
- `src/main/kotlin/attractor/events/PipelineEvent.kt` — Add `DiagnosticsStarted`,
  `DiagnosticsCompleted` event types.
- `src/main/kotlin/attractor/web/PipelineState.kt` — Handle new events (log entries + stage status).
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — Wire `FailureDiagnoser` into `EngineConfig`.
- `src/main/kotlin/attractor/llm/Client.kt` — `generate()` and `Client.fromEnv()` already available.
- New file: `src/main/kotlin/attractor/engine/FailureDiagnoser.kt`

## Constraints

- Must follow project conventions in CLAUDE.md (no CLAUDE.md present; follow existing patterns)
- Must integrate **after** existing retry/routing exhaustion, not instead of it — existing
  `retryTarget` / `fallbackRetryTarget` / `goalGate` mechanics are unchanged
- Must degrade gracefully when no LLM API key is present: `NullDiagnoser` or rule-based fallback
- Simulation mode (`options.simulate = true`): skip LLM diagnostic call, log "simulation: no diagnosis"
- Only one repair attempt per failed stage (no infinite diagnosis loops)
- No new Gradle dependencies

## Success Criteria

- A stage that fails due to a diagnosable transient error (e.g., API rate limit, malformed output)
  gets a second chance after the engine injects the diagnostic hint into context
- The repair attempt uses the same handler as the original stage with enriched context
- Failures that are classified as unrecoverable produce a `failure_report.json` in the run's
  `logsRoot` with structured diagnosis info (failed node, reason, explanation, strategy)
- The web monitor shows a "Diagnosing…" status during the LLM diagnostic call, and the diagnosis
  result (explanation + strategy) is visible in the stage detail or pipeline log

## Verification Strategy

- Spec/documentation: The seed prompt defines correct behavior; success criteria above is the spec
- Testing approach: Manual end-to-end testing with a pipeline that has a failing stage; verify
  `failure_report.json` is written, repair attempt fires, and the web UI shows diagnosis state
- Edge cases:
  - Repair attempt also fails → halt with report
  - No API key present → NullDiagnoser, halt immediately with message
  - Simulation mode → skip diagnosis, halt
  - Node has `failure_diagnosis_disabled=true` → skip diagnosis entirely
  - Exception thrown inside diagnoser → catch, log, halt without repair

## Uncertainty Assessment

- Correctness uncertainty: **Medium** — LLM behavior is non-deterministic; fix classification
  quality depends on prompt quality, but the worst case is the same halt-and-log behavior as today
- Scope uncertainty: **Medium** — "fix it" is vague; we interpret it as context injection +
  single repair retry, which is bounded and reversible
- Architecture uncertainty: **Low** — Integration point in Engine is clear; LLM call pattern is
  already established in `LlmCodergenBackend`

## Open Questions

1. Should the repair hint be injected as a context variable (`repair.hint`) that nodes can
   reference in their prompts, or should the diagnoser construct a modified prompt outright?
2. Should the `FailureDiagnoser` be aware of the full pipeline context (all prior stage outputs)
   or just the failing stage's info?
3. Should there be a `failure_diagnosis_disabled` node attribute to opt out per-node, or just
   a global `EngineConfig` flag?
4. Should a successful repair attempt (node succeeds on repair retry) emit a special event or
   just the standard `StageCompleted`?
