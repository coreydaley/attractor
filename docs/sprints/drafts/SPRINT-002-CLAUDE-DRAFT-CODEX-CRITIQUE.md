# Critique: SPRINT-002-CLAUDE-DRAFT

## Overall Assessment

Claude's draft is strong and mostly implementation-ready. It maps cleanly to the sprint intent, keeps scope bounded to the right files, and uses existing architecture (`dotSource` in pipeline snapshots + streaming endpoint pattern) rather than inventing new persistence.

## What Works Well

1. The user flow is clear and aligned with intent: Monitor terminal run -> Iterate -> Modify or direct edit -> Run as new tab.
2. The plan correctly reuses existing streaming conventions (`/api/generate/stream`, `/api/fix-dot`) for `/api/iterate/stream`.
3. It explicitly protects the original run from mutation and keeps iteration as a new submission path.
4. It anticipates key UX hazards (iterate on old runs with blank `dotSource`, disable controls during modify stream).
5. Scope discipline is good: no DB schema expansion, no dependency changes, no prompt-history detour.

## Gaps / Concerns

1. **Iterate mode lifecycle wording is internally inconsistent**: the task says to call `exitIterateMode()` in `showView('create')` "when transitioning away from Create." If implemented literally in the create branch, it can clear iterate mode at the wrong time. This should be stated as "when leaving Create" (i.e., any target view except create).
2. **Input validation is underspecified for backend endpoint**: the draft describes endpoint wiring but does not explicitly require rejecting empty `baseDot` and empty `changes` with 400 responses, matching existing API conventions.
3. **Prompt/token risk statement is technically weak**: `max_tokens=8192` is output budget, not full context capacity. The mitigation should acknowledge model context limits and recommend truncation/guard rails if needed.
4. **CSS proposal adds a new purple action style without rationale**: introducing a new color class for `Iterate` is unnecessary given the "minimal CSS" constraint; reusing existing button styles lowers risk and keeps UI consistency.
5. **Build verification command is environment-coupled**: hardcoding a local Gradle distro path is brittle for team reproducibility. Prefer wrapper-based verification unless there is a strict repo policy requiring otherwise.

## Recommended Adjustments

1. Clarify iterate-mode cleanup trigger as "on navigation away from Create" and show exact condition.
2. Add explicit API validation requirements for `/api/iterate/stream` request fields and HTTP status codes.
3. Rewrite context-limit risk/mitigation to separate input size from output token budget.
4. Reuse existing button classes (`.btn-rerun`/`.run-btn`) before adding new color styles.
5. Standardize build verification command to a reproducible team default.

## Verdict

The draft is directionally correct and close to execution-ready. With the lifecycle and validation clarifications above, it will be robust enough to implement directly with low ambiguity.
