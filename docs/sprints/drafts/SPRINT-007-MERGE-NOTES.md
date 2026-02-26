# Sprint 007 Merge Notes

## Claude Draft Strengths
- Comprehensive 6-phase breakdown with detailed per-task checklists
- SKIP strategy (user explicitly confirmed — useful for non-critical stages)
- Detailed LLM system prompt template for diagnosis
- `failure_report.json` format specified with all fields
- Separate `_repair` subdir for repair attempt logs (user confirmed)
- Repair context injection details (`repair.hint`, `repair.explanation`, `repair.attempt`)
- Detailed CSS/JS for "Diagnosing…" / "Repairing…" stage status in the web monitor

## Codex Draft Strengths
- `FailureContext` data class wrapping all diagnoser inputs (cleaner interface vs raw params)
- `DiagnosisResult` naming (cleaner than `FailureAnalysis`)
- `recoverable: Boolean` field (cleaner than `canFix`)
- Events carry `nodeId` not just `stageName` (critical correctness fix)
- Simpler event model as starting point (defer extra repair events if not needed)
- `FailureReport` schema and JSON writer as an explicit artifact
- Test phase (Phase 5): EngineTest coverage for repair + report writing
- Use first-available provider instead of hard-coding a specific model string

## Valid Critiques Accepted

1. **Events should carry `nodeId`**: `PipelineState` updates stages by `nodeId`, not `stageName` alone.
   Using `stageName` only risks updating the wrong stage record when two nodes share a label.
   → Events: use `nodeId` (+ `stageName` for display) in `DiagnosticsStarted/Completed`.

2. **Hard-coded `claude-haiku-4-5-20251001` → use `LlmCodergenBackend.DEFAULT_MODEL`**: The model
   constant already exists in `LlmCodergenBackend` and is provider-tested. Use it as default.
   `LlmFailureDiagnoser` can accept an override, but defaults to `DEFAULT_MODEL`.

3. **Browser-side `File.exists()` is impossible**: Can't check server filesystem from JS.
   → Add `"hasFailureReport": true/false` to the pipeline state JSON snapshot. `PipelineRunner`
   sets it when `writeFailureReport()` runs. The UI checks `p.state.hasFailureReport`.

4. **Prefer stage artifacts for diagnostic input**: `node.prompt` is pre-expansion and may be
   stale. The `logsRoot/<nodeId>/prompt.md`, `response.md`, and `live.log` files contain the
   actual runtime content. → `FailureContext` references `logsRoot` and reads artifacts directly.

5. **Explicit `@Serializable` annotations**: `DiagnosisResult` and `FailureReport` need
   `@Serializable` or manual JSON construction (the project uses both). Use manual `buildJsonObject`
   for `FailureReport` (consistent with existing `Engine.kt` manifest pattern) and `@Serializable`
   on `DiagnosisResult` for parsing the LLM response.

6. **Include test phase**: Add Phase 6 (Tests) — `EngineTest.kt` coverage for repair + report.

## Critiques Rejected (with reasoning)

- **"Omit SKIP"**: User explicitly confirmed including `SKIP` during the interview as useful for
  non-critical stages. It stays. We add `failure_diagnosis_allow_skip=true` as a per-node opt-in
  guard so the LLM can only SKIP nodes where the author explicitly permits it. This addresses the
  "silently advancing" risk.

- **"Defer `RepairAttempted/Succeeded/Failed` events"**: The web monitor needs these to show
  "Repairing…" stage status during the repair attempt. Without `RepairAttempted`, there's no way
  to transition the stage from "diagnosing" to "repairing" in the UI. They stay, but we note that
  `RepairSucceeded` is optional if we rely on standard `StageCompleted` for the successful path.

## Interview Refinements Applied

- Fix mechanism: context injection + single retry confirmed → kept as designed
- SKIP strategy: include → kept with `failure_diagnosis_allow_skip=true` guard
- Repair logs: separate `_repair` subdir (`logsRoot/<nodeId>_repair/`) confirmed → kept

## Final Decisions

1. Data model: `FailureContext` (Codex) + `DiagnosisResult` (Codex) → both adopted
2. Strategy enum: `RETRY_WITH_HINT`, `SKIP`, `ABORT` (kept from Claude, user-confirmed)
3. Events carry `nodeId` (Codex critique)
4. Default model: `LlmCodergenBackend.DEFAULT_MODEL` (Codex critique)
5. `hasFailureReport` boolean in state JSON (Codex critique)
6. Stage artifacts as diagnoser input (Codex critique)
7. `failure_diagnosis_allow_skip=true` node attribute required for SKIP to be used (safety gate)
8. Repair logs in `logsRoot/<nodeId>_repair/` (user interview)
9. Test phase included (Codex draft)
10. `RepairAttempted`/`RepairSucceeded`/`RepairFailed` events kept (needed for UI state)
