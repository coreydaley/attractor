# Critique: SPRINT-013-CLAUDE-DRAFT

## Overall Assessment

The draft is well-structured and close to implementation-ready, but it misses a confirmed Sprint 013 requirement and has a few concrete accuracy gaps that would cause avoidable rework.

## High-Priority Findings

1. **Missing required fourth tab (`DOT Format`)**
   - Reference: `docs/sprints/drafts/SPRINT-013-CLAUDE-DRAFT.md:7`, `:41`, `:97-99`, `:259`
   - Sprint 013 intent explicitly confirmed adding a fourth tab: **DOT Format**.
   - The draft consistently plans only three tabs (Web App, REST API, CLI), so it does not satisfy the accepted scope.
   - Fix: add DOT Format tab to architecture, phases, tests, files summary, and DoD; include syntax/attributes/examples coverage.

2. **Definition of Done does not enforce intent-complete content surface**
   - Reference: `docs/sprints/drafts/SPRINT-013-CLAUDE-DRAFT.md:255-271`
   - DoD validates only three tabs and lacks any acceptance checks for DOT documentation markers.
   - Fix: add explicit DOT DoD criteria and test assertions (tab label + key DOT sections/examples present).

## Medium-Priority Findings

1. **Default port is documented inconsistently with current project defaults**
   - Reference: `docs/sprints/drafts/SPRINT-013-CLAUDE-DRAFT.md:122`, `:203`
   - Draft repeatedly uses `http://localhost:8080`, while repository docs and runtime UX are centered on port `7070` unless overridden.
   - Fix: standardize examples to `7070` (or explicitly state “configured port, default 7070”).

2. **Route handling scope is ambiguous for `/docs/*` paths**
   - Reference: `docs/sprints/drafts/SPRINT-013-CLAUDE-DRAFT.md:245`, `:299`
   - The draft acknowledges prefix matching but does not define whether `/docs/foo` should serve docs, 404, or redirect.
   - Fix: define an explicit route policy and test it (`/docs` and `/docs/` behavior, reject unrelated `/docs/*` if desired).

3. **Test plan verifies labels but not tab-content integrity for completeness claims**
   - Reference: `docs/sprints/drafts/SPRINT-013-CLAUDE-DRAFT.md:236-246`
   - Current tests check basic markers but not that REST section actually includes complete endpoint groups and that CLI/DOT sections include expected command/syntax anchors.
   - Fix: add stable content-marker assertions per tab to guard against partial/placeholder docs.

## Suggested Edits Before Implementation

1. Add a full **DOT Format** tab throughout the plan (architecture, implementation phases, tests, DoD).
2. Update all port examples/default references to align with current project default (`7070`) or make configurability explicit.
3. Define exact `/docs` path behavior for trailing slash and deeper paths, then encode in tests.
4. Strengthen tests with section-level markers proving content completeness, not just tab headings.

## Bottom Line

The draft is solid structurally, but it is not intent-complete until DOT Format is added as a first-class tab and acceptance target. After that correction plus minor accuracy/test hardening, it is implementation-ready.
