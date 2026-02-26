# Critique: SPRINT-007-CLAUDE-DRAFT

## Overall Assessment

Good alignment with the Sprint 007 intent: it hooks diagnosis after retry/routing exhaustion, keeps the repair attempt bounded to 1, and specifies a concrete `failure_report.json`. The draft is close to “implementation-ready”, but a few items conflict with the current codebase realities and/or expand scope in risky ways.

## High-Priority Findings

1. **Event payloads should include `nodeId` (not just `stageName`)**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:181`  
The proposed events only carry `stageName` + `stageIndex`. In the actual runtime model, `label`/name is not guaranteed unique, and `PipelineState` currently updates stages primarily by `name` (see your own plan at `:233-246`). This can lead to updating the wrong stage record.  
**Fix:** Include `nodeId` in `DiagnosticsStarted/Completed` (and any repair-related events if kept) and update `PipelineState` using `nodeId` (preferred) or `(nodeId, index)` rather than `stageName`.

2. **Model hard-coding looks incorrect / non-portable**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:207-213`  
The plan hard-codes `generate(model = "claude-haiku-4-5-20251001", ...)`. This may not exist in your current provider catalog and also undermines multi-provider support (`Client.fromEnv()` can pick OpenAI/Gemini/etc).  
**Fix:** Default to the existing “first available provider” behavior (or reuse a known in-repo default), and optionally allow a config override later.

3. **Client-side `File(...).exists()` is not implementable in the browser**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:364-366`  
The draft suggests checking `File(logsRoot, "failure_report.json").exists()` in `WebMonitorServer` embedded JS. That code runs in the browser and cannot access the server filesystem.  
**Fix:** Either (a) always show a “Failure report” link when pipeline status is failed, and have the endpoint 404 if absent, or (b) add a boolean like `hasFailureReport` to the server JSON snapshot.

4. **`SKIP` strategy is a scope/risk expansion beyond the intent**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:68-72`, `:295-305`, `:431-432`  
The intent asks for “repair and rerun if so — or write a report and halt if not.” Adding “skip stage” introduces semantics that can silently advance a broken pipeline (especially if the stage is non-optional).  
**Fix:** Drop `SKIP` for Sprint 007 (keep only “repair once” or “halt with report”), or require an explicit node attribute opt-in (e.g., `diagnoser_allow_skip=true`) if you keep it.

## Medium-Priority Findings

1. **Repair attempt timing / duration fields are inconsistent**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:276-285`  
`RepairSucceeded(..., durationMs = elapsed())` uses pipeline-level elapsed time, not the repair attempt duration. Also, emitting `RepairSucceeded` and separately emitting `StageCompleted` (via normal engine flow) risks redundant UI signals.  
**Fix:** Measure repair attempt duration locally if needed, and consider relying on existing stage lifecycle events + diagnostics events only.

2. **Prompt sampling should prefer stage artifacts over raw node prompt**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:145-149`  
Using `node.prompt` misses variable expansion and any transforms; in practice, `logsRoot/<nodeId>/prompt.md`, `response.md`, `status.json`, and `live.log` are the most faithful “what happened” inputs.  
**Fix:** Build the diagnoser input primarily from stageDir artifacts, with truncation caps.

3. **JSON parsing details are under-specified for Kotlin serialization**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:210-212`  
`Json.decodeFromString(responseText) → FailureAnalysis` needs a serializer (e.g., `@Serializable` on `FailureAnalysis` or manual parsing).  
**Fix:** Explicitly declare the serialization approach in the plan so the implementation is straightforward.

4. **Extra events add complexity without being required by the intent**  
Reference: `docs/sprints/drafts/SPRINT-007-CLAUDE-DRAFT.md:179-193`, `:215-226`  
The intent explicitly calls out `DiagnosticsStarted`/`DiagnosticsCompleted`. Adding `RepairAttempted/Succeeded/Failed` is plausible, but it increases UI/state surface area and can be deferred.  
**Fix:** Start with diagnostics events + existing stage events; add repair-specific events only if UX truly needs them.

## Suggested Edits Before Implementation

1. Make events identify the stage by `nodeId` (and update state tracking accordingly).
2. Replace the hard-coded model with a repo-consistent default and/or provider-agnostic choice.
3. Remove or strongly gate `SKIP` (keep the sprint bounded to “repair once or halt”).
4. Replace the browser-side filesystem check with a server-provided flag or a tolerant link.
5. Prefer stage artifacts (`prompt.md`, `response.md`, `status.json`, `live.log`) as diagnoser inputs.

## Bottom Line

The draft has the right feature shape and good boundedness for the repair loop, but it should be tightened to match the real execution/UI environment (server vs browser) and to avoid risky scope creep (`SKIP`, extra events, hard-coded model). With those adjustments, it becomes a clean, implementable Sprint 007 plan.

