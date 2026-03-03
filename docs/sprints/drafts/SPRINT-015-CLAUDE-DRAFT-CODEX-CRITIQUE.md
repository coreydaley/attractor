# Critique: SPRINT-015-CLAUDE-DRAFT

## Overall Assessment

The draft is strong on structure and generally aligned with the Sprint 015 intent, especially around hermetic test patterns and broad surface coverage. The main issues are implementation-accuracy gaps: a few planned assertions conflict with current code behavior, and several acceptance targets are left intentionally ambiguous in ways that weaken regression value.

## High-Priority Findings

1. **Incorrect claim about `POST /api/v1/pipelines` execution behavior**
- Reference: `docs/sprints/drafts/SPRINT-015-CLAUDE-DRAFT.md:85`, `:292`
- The draft claims `PipelineRunner.submit` is not started in test context because `onUpdate` is a no-op. In current code, `handleCreatePipeline` directly calls `PipelineRunner.submit(...)` regardless of `onUpdate` callback behavior.
- Why this matters: it can lead to wrong test setup assumptions and brittle racey assertions.
- Fix: treat create-pipeline success as an actual submit path and design assertions around response contract, not around a presumed non-execution path.

2. **Several route expectations are intentionally non-deterministic (`200 or 400`, `200 or 409`, etc.)**
- Reference: `docs/sprints/drafts/SPRINT-015-CLAUDE-DRAFT.md:87`, `:90`, `:95`, `:98`, `:112`, `:197-200`
- Multiple tasks allow dual outcomes instead of pinning current behavior. This makes failing behavior look “acceptable” and reduces test value as a regression signal.
- Why this matters: sprint intent is coverage closure, not ambiguous behavior capture.
- Fix: read handlers first and assert exact status/content-type/error code for each route.

3. **`dot render` CLI test plan mismatches implementation contract**
- Reference: `docs/sprints/drafts/SPRINT-015-CLAUDE-DRAFT.md:152`
- The draft says render writes “SVG bytes,” but `DotCommands.render` expects JSON (`{"svg":"..."}`), extracts the string, and writes text to file.
- Why this matters: a byte-stream test fixture will be invalid for current implementation and could fail for the wrong reason.
- Fix: test `POST /api/v1/dot/render` JSON response parsing and output file text contents.

## Medium-Priority Findings

1. **File plan inconsistency for spec-route tests**
- Reference: `docs/sprints/drafts/SPRINT-015-CLAUDE-DRAFT.md:39`, `:80`, `:225`
- Architecture mentions “`RestApiSpecTest NEW`,” but implementation/files summary only modifies `RestApiRouterTest.kt` and never adds the new file.
- Fix: either add the new file explicitly in phases/files summary or remove the architecture claim and keep everything in `RestApiRouterTest.kt`.

2. **Some expected status codes likely conflict with current WebMonitorServer handlers**
- Reference: `docs/sprints/drafts/SPRINT-015-CLAUDE-DRAFT.md:205`
- The draft expects `DELETE /api/pipelines` -> `405`, but current `/api/pipelines` handler has no explicit non-GET branch. That expectation may fail unless production code is changed.
- Fix: either (a) explicitly include method-guard hardening in scope, or (b) test currently documented/actual behavior for this route.

3. **Swagger endpoint expectation is too loose for current implementation**
- Reference: `docs/sprints/drafts/SPRINT-015-CLAUDE-DRAFT.md:112`
- Draft says “swagger UI redirect or JSON,” but current `/api/v1/swagger.json` handler serves HTML (Swagger UI shell) with 200.
- Fix: assert `200` + `text/html` + stable marker (e.g., `SwaggerUIBundle`) instead of redirect/JSON alternatives.

## Suggested Edits Before Implementation

1. Replace all “X or Y” status expectations with exact assertions based on current handlers.
2. Correct the create-pipeline execution assumption (`PipelineRunner.submit` is called).
3. Align `dot render` CLI tests with JSON response contract (`svg` field), not raw bytes.
4. Resolve the spec-test file inconsistency (create dedicated spec test file or keep it intentionally in `RestApiRouterTest`).
5. Tighten browser API method-guard expectations where handlers currently lack explicit guards.

## Bottom Line

This draft is close to implementation-ready, but it needs a pass for behavioral precision. Once expectations are made deterministic and a few code-contract mismatches are corrected, it will be a reliable sprint plan for coverage closure.
