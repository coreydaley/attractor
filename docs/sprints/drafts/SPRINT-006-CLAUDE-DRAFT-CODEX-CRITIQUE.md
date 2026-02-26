# Critique: SPRINT-006-CLAUDE-DRAFT

## Overall Assessment

Strong direction and mostly aligned with Sprint 006 intent. The draft is close to executable, but a few high-impact issues should be corrected before implementation.

## High-Priority Findings

1. **Violates sprint read-only constraint for historical loads**  
Reference: `docs/sprints/drafts/SPRINT-006-CLAUDE-DRAFT.md:236`  
The draft explicitly states loaded-from-DB runs should allow `[Re-run], [Iterate], [Export], [Artifacts]` with no special-casing. The intent requires historical runs loaded for viewing to be read-only (no rerun from ghost entries).  
**Fix:** Add explicit `viewOnly` semantics for on-demand loaded entries and gate mutating actions in the panel.

2. **`pendingSelectId` hook is wired to the wrong payload shape**  
Reference: `docs/sprints/drafts/SPRINT-006-CLAUDE-DRAFT.md:187`  
The proposal checks `if (pendingSelectId && data.id === pendingSelectId)` inside `applyUpdate(data)`. Current `applyUpdate` consumes `data.pipelines[]`, not `data.id`, so this condition will not fire reliably.  
**Fix:** Consume `pendingSelectId` after the merge loop by checking `pipelines[pendingSelectId]`, then call `selectTab(...)`.

3. **`PipelineRegistry` pseudocode uses a non-existent lock primitive**  
Reference: `docs/sprints/drafts/SPRINT-006-CLAUDE-DRAFT.md:134`  
The sketch uses `synchronized(lock)` but current `PipelineRegistry` has no `lock` field.  
**Fix:** Either add a concrete lock object in the plan or use existing thread-safe structures with a consistent insertion strategy.

## Medium-Priority Findings

1. **Terminology conflict: “ephemeral” vs “persist for session”**  
Reference: `docs/sprints/drafts/SPRINT-006-CLAUDE-DRAFT.md:13`  
The draft calls tab semantics “ephemeral” but then says loaded tabs persist for the session identically to normal runs.  
**Fix:** Pick one term and behavior (`session-persistent` appears to match the described behavior).

2. **Architecture section still shows `alert(...)` while later phase says inline errors**  
References: `docs/sprints/drafts/SPRINT-006-CLAUDE-DRAFT.md:60`, `:232`  
This creates implementation ambiguity.  
**Fix:** Standardize on inline error handling only.

3. **Over-coupling to broadcast/poll for local selection**  
References: `docs/sprints/drafts/SPRINT-006-CLAUDE-DRAFT.md:61-66`, `:123-126`  
The flow waits for broadcast + poll before selecting, even though `/api/load-run` already returns success.  
**Fix:** After successful load, either fetch `/api/pipelines` once and select immediately when present, or return the hydrated pipeline payload directly to avoid extra state convergence complexity.

## Suggested Edits Before Implementation

1. Add explicit read-only behavior for on-demand historical entries and action gating in UI.
2. Correct `pendingSelectId` consumption logic to match `applyUpdate`’s `data.pipelines[]` schema.
3. Replace `synchronized(lock)` pseudocode with a concrete, codebase-consistent synchronization plan.
4. Resolve wording/behavior inconsistencies (`ephemeral` and error-handling approach).

## Bottom Line

The draft has a solid feature shape and good scope control (two-file change). With read-only enforcement and the selection/synchronization bugs fixed, it is execution-ready.
