# Sprint 002 Merge Notes

## Claude Draft Strengths
- Detailed per-task implementation plan with code sketches for `iterateStream()`
- Well-organized percentage-weighted phases
- Complete risk table with Medium/High ratings
- Explicit component interaction diagram showing Monitor → Create → API → Monitor flow
- Good anticipation of UX hazards (blank dotSource, in-progress streaming disables buttons)
- Security section reuses existing `js()` escaping helper

## Codex Draft Strengths
- Explicitly requires 400 validation for empty `baseDot` or `changes` on the backend endpoint
- Clean text-based architecture diagram matching existing `architecture diagram` style
- Calls out reusing existing `/api/fix-dot` auto-fix path after `Modify` completes (good catch)
- Explicit note about preserving `simulate/autoApprove` checkbox state during iterate mode

## Valid Critiques Accepted
1. **Lifecycle clarity** (Critique #1): `exitIterateMode()` should be called "when navigating away from Create view" — clarified as `showView(nonCreate)` triggers it, NOT the `showView('create')` call itself. The implementation note now states: exit iterate mode in `showView()` for all views except `'create'`.
2. **Backend validation** (Critique #2): Added explicit requirement to reject empty `baseDot` and empty `changes` with 400 JSON error responses, matching existing endpoint conventions.
3. **Token/context risk** (Critique #3): Rewrote risk entry to distinguish model context window (input) from `max_tokens` (output budget). Added note that very large DOT files may exceed context; recommendation is to keep pipelines bounded.
4. **CSS reuse** (Critique #4): Dropped the new `.btn-iterate` purple class. Iterate button reuses `.btn-rerun` styling. This is simpler and keeps the button bar visually consistent.

## Critiques Rejected (with reasoning)
5. **Build verification command** (Critique #5): Project explicitly requires native Gradle 8.7 via `~/.gradle/wrapper/dists/...` path (see MEMORY.md). `./gradlew` fails on the project's Java 25 environment. Keeping the MEMORY-prescribed build command.

## Interview Refinements Applied
- Confirm: "Describe + Modify button" flow (user confirmed in interview)
- Confirm: Iterate button appears only for terminal states (completed, failed, cancelled) — not paused

## Final Decisions
- `iterateStream()` delegates to `generateStream()` with a structured prompt containing the base DOT
- `/api/iterate/stream` validates both `baseDot` and `changes` as non-empty (400 if missing)
- After Modify stream completes, trigger existing `renderGraph()` cycle (which auto-fixes broken DOT)
- `exitIterateMode()` is called when `showView()` is called with any non-`'create'` view name
- Iterate button uses `.btn-rerun` CSS class (no new style)
- Iterate button is visible only for `completed | failed | cancelled` (not `paused`, not `archived`)
- Original run is never mutated; iterated pipeline always creates a new `PipelineRunner.submit()` call
