# Sprint 013 Merge Notes

## Claude Draft Strengths
- Highly detailed per-phase task lists with granular, concrete todos
- Thorough content outline for each tab (specific sections and subsections)
- Good UX details: `localStorage` tab memory, collapsible `<details>/<summary>` for REST endpoint groups
- Strong test plan with specific content strings to assert
- Security considerations section
- Proposed `webAppTabHtml()`, `restApiTabHtml()`, `cliTabHtml()` helper functions for maintainability

## Codex Draft Strengths
- Correctly framed four tabs from the start (Web App, REST API, CLI, DOT Format)
- "Content Source Strategy" table makes the content derivation explicit and traceable
- Dedicated maintainability phase for splitting into helper builders
- Flags docs-drift as a medium-high risk
- Clean, explicit DoD
- Raised explicit route policy question for `/docs/*` paths (sub-paths)
- Framed regression validation as its own phase

## Valid Critiques Accepted

1. **Missing DOT Format tab** (HIGH) — Claude's draft was written before the interview added this requirement. The DOT Format tab is incorporated as Phase 6 content in the merged draft with its own phase, DoD criteria, and test assertions.

2. **Port should be 7070, not 8080** (MEDIUM) — README and source confirm the default port is `7070`. All REST API examples in the docs page will use `http://localhost:7070`.

3. **Route handling for `/docs/*` paths** (MEDIUM) — The merged draft explicitly defines that `/docs` and `/docs/` serve the full docs page, and any deeper path (e.g., `/docs/foo`) returns 404. This is tested.

4. **Stronger content-marker tests** (MEDIUM) — Test plan expanded with per-tab section markers (REST endpoint groups, CLI commands, DOT syntax keywords).

## Critiques Rejected

- None. All Codex critiques were substantive and accepted.

## Interview Refinements Applied

- Q1: Full REST API spec (all 35 endpoints with curl examples) → Phase 5 covers this fully
- Q2: Add fourth "DOT Format" tab → Phase 6, DoD, and tests all include DOT Format

## Final Decisions

1. **Four tabs**: Web App, REST API, CLI, DOT Format
2. **Port**: `7070` in all documentation examples
3. **Route policy**: `/docs` and `/docs/` → serve docs page; `/docs/*` deeper paths → 404
4. **Helper functions**: `docsPageShell()`, `webAppTabContent()`, `restApiTabContent()`, `cliTabContent()`, `dotFormatTabContent()` — keep `WebMonitorServer.kt` organized
5. **Tab memory**: Use `localStorage` (`attractor-docs-tab`) to remember last selected tab across page refreshes
6. **Test file name**: `DocsEndpointTest.kt` (Claude) rather than `WebMonitorServerDocsTest.kt` (Codex) — shorter, follows project naming patterns
7. **Collapsible endpoint groups**: Use `<details>/<summary>` for REST API endpoint groups (from Claude) to keep the long spec navigable
8. **Phase ordering**: Navigate → Shell → Web App → REST API → CLI → DOT Format → Refactor → Tests (logical content-first order)
