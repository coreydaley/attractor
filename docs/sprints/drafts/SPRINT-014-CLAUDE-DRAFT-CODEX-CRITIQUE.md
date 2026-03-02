# Critique: SPRINT-014-CLAUDE-DRAFT

## Overall Assessment

The draft is clear, implementation-shaped, and closely matches the Sprint 014 intent. It correctly reuses existing patterns (Import modal `FileReader`, Create view `renderGraph()`), and its DoD mostly captures the success criteria. A few concrete gaps would likely cause regressions or surprising UX if implemented as written.

## High-Priority Findings

1. **Uploaded DOT can be overwritten by pending NL auto-generate debounce**
   - Reference: `docs/sprints/drafts/SPRINT-014-CLAUDE-DRAFT.md:7`, `:19`, `:93-118`
   - The Create view auto-generates DOT as the user types in `#nlInput` (debounced). If the user types a prompt, then uploads a file before the debounce fires, the pending generate/iterate request can still run and replace the uploaded DOT content.
   - Fix: in `onDotFileSelected()`, explicitly cancel any in-flight/pending generate timers (`genDebounce`, `genCountdown`) and set an unambiguous status (“Loaded from file …”) that won’t be immediately replaced by the generator’s status updates unless the user edits `#nlInput` again.

2. **Reset coverage is incomplete (`resetCreatePage()` also needs file-input reset)**
   - Reference: `docs/sprints/drafts/SPRINT-014-CLAUDE-DRAFT.md:58-64`, `:127-146`
   - The plan resets `uploadedFileName` and the file input in `clearCreateForm()`, but the success flow after `runGenerated()` calls `resetCreatePage()` (not `clearCreateForm()`), so the hidden file input can remain “sticky” across runs.
   - Fix: reset both `uploadedFileName` and `#dotFileInput.value` in `resetCreatePage()` as well.

3. **Test plan underestimates what is testable at HTTP level**
   - Reference: `docs/sprints/drafts/SPRINT-014-CLAUDE-DRAFT.md:166-176`
   - While file reading can’t be exercised in server-side tests, the presence of the upload control (and its wiring hooks) can be regression-tested by asserting `GET /` contains `accept=".dot"`, the input id, and handler name.
   - Fix: add a small dedicated test (or extend an existing web test) asserting the markup/JS hooks exist on `/`.

## Medium-Priority Findings

1. **`dot-header-row` layout likely breaks without a small wrapper**
   - Reference: `docs/sprints/drafts/SPRINT-014-CLAUDE-DRAFT.md:79-83`
   - Today `.dot-header-row` is a flex row with `justify-content: space-between` and two children (`<h2>` and `#genStatus`). Adding a third sibling (upload button) will likely produce awkward spacing (status drifting to the middle) unless `#genStatus` and the button are wrapped in a right-aligned container.
   - Fix: wrap status + upload button in a `<div style="display:flex;gap:8px;align-items:center;">…</div>` or update `.dot-header-row` CSS to handle three children intentionally.

2. **Some statements about UI actions are likely inaccurate**
   - Reference: `docs/sprints/drafts/SPRINT-014-CLAUDE-DRAFT.md:21`
   - The draft mentions the user can “click Fix” after a DOT render error, but the Create view appears to auto-attempt DOT fixing during `renderGraph()` retries rather than exposing a dedicated Fix button.
   - Fix: either remove “click Fix” from use cases or explicitly specify the UI affordance being referenced.

3. **“No new Kotlin code” is misleading in this repo’s architecture**
   - Reference: `docs/sprints/drafts/SPRINT-014-CLAUDE-DRAFT.md:9`
   - All of the HTML/CSS/JS is emitted from `WebMonitorServer.kt`, so this is still a Kotlin code change (and the draft also proposes updating `webAppTabContent()`).
   - Fix: rephrase as “no new server routes / no new Kotlin server handlers / no new dependencies”.

## Suggested Edits Before Implementation

1. Add explicit cancellation of NL generation debounce/timers on file upload to prevent DOT overwrite.
2. Reset both `#dotFileInput.value` and `uploadedFileName` in `resetCreatePage()` in addition to `clearCreateForm()`.
3. Add a small HTTP-level regression test for Create view upload markup (input id + `accept=".dot"` + handler hook).
4. Adjust the `.dot-header-row` plan to include a wrapper or explicit layout behavior for status + upload button.

## Bottom Line

The plan is close to implementation-ready and intent-complete, but it needs a small amount of additional hardening around Create view debounced generation interactions, reset behavior, and layout/testing details to avoid regressions and confusing UX.
