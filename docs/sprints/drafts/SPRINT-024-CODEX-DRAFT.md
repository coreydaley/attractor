# Sprint 024: Documentation Audit and Update

## Overview

Attractor’s documentation is currently split across three user-facing surfaces: the repository `README.md`, the Hugo docs microsite under `docs-site/` (published to GitHub Pages), and the developer/machine reference at `docs/api/rest-v1.md`. Sprints 021–023 and recent chores introduced meaningful UI, API, Docker, and runtime changes (Git History Panel, the docs microsite migration, split Docker images, Java/Kotlin/Gradle upgrades) that were not fully carried through to all doc surfaces.

This sprint focuses on a **documentation-only** audit and update to remove stale references (especially the legacy in-app `/docs` page), correct mismatched terminology (e.g. `dot` vs `graphviz` as the system tool label), fix factual inaccuracies (wrong default port, stale endpoint counts, incorrect Java version references), and ensure recently added features (Git History Panel, additional DOT node types) are documented clearly and consistently.

The goal is to make the README and docs site reliable “source of truth” onboarding material again, while keeping `docs/api/rest-v1.md` accurate for automation and deeper technical integration.

## Use Cases

1. **Accurate onboarding**: A new user follows `README.md` and successfully builds/runs the server, finds the correct documentation site, and understands the current UI layout without encountering references to removed pages or tabs.

2. **Feature discovery**: A user reading `docs-site/content/web-app.md` learns about the Git History Panel and how it relates to project workspaces and the `/api/v1/projects/{id}/git` endpoint.

3. **Correct API integration**: A developer uses `docs/api/rest-v1.md` (or the Hugo REST API page) and hits the right base URL/port, sees endpoints that match `RestApiRouter.kt`, and can confidently automate project lifecycle/actions.

4. **Correct DOT authoring**: A user reading `docs-site/content/dot-format.md` sees all supported orchestration node types (including parallel fan-out/fan-in, tool nodes, and stack manager loops) and can author DOT that matches the runtime behavior.

## Architecture

This sprint treats code as the authoritative truth and updates docs to match it.

**Sources of truth to verify against:**
- REST endpoints: `src/main/kotlin/attractor/web/RestApiRouter.kt`
- Web UI behavior and terminology: `src/main/kotlin/attractor/web/WebMonitorServer.kt`
- DOT node shape → handler mapping: `CLAUDE.md` (and corroborated by handler registry conventions)
- Docker runtime Java version: `Dockerfile.base`

**Documentation surfaces to update:**
- `README.md` (repo landing page / canonical quick start)
- Hugo docs pages under `docs-site/content/` (user-facing docs site)
- `docs/api/rest-v1.md` (developer/machine REST reference)

## Implementation Plan

### Phase 1: Inventory + truth-check checklist (~15%)

**Files:**
- (No doc changes required in this phase; this is an execution checklist for the sprint.)

**Tasks:**
- [ ] Build a “facts checklist” from code:
  - Web default port (expected: 7070) and where it’s configured/overridden
  - REST endpoint list and total count (from `RestApiRouter.kt`)
  - Current UI layout (outer nav views + inner project tab layout) and presence of Git History Panel
  - DOT node type mapping for advanced shapes (`component`, `tripleoctagon`, `parallelogram`, `house`)
  - Docker base image runtime Java version (`Dockerfile.base`)
- [ ] Run targeted searches for stale references across docs:
  - `/docs` (distinguish removed root route vs existing `/api/v1/docs` swagger UI)
  - `8080` default port references
  - `dot` vs `graphviz` label usage (system tools messaging)
  - Java version references (21 vs 25), especially “JDK 21” in build/run guidance
  - “35 endpoints”/stale endpoint counts

---

### Phase 2: Update `README.md` to current product reality (~35%)

**Files:**
- `README.md` — Modify

**Tasks:**
- [ ] Remove/replace stale references to the legacy in-app `/docs` page; ensure the README consistently points to the external Hugo docs site.
- [ ] Update API claims:
  - Endpoint count language updated to match current `RestApiRouter.kt` (37+ as of Sprint 021).
  - Remove any references to a `GET /docs` runtime route (legacy) while avoiding confusion with `/api/v1/docs` (swagger UI).
- [ ] Update terminology to match the UI:
  - Replace required tool label `dot` → `graphviz` where describing system tool warnings/settings.
- [ ] Correct Java version references in build options tables (e.g. “Path to JDK 21” → “Path to JDK 25”).
- [ ] Update any “project structure” descriptions that still mention a removed `/docs` endpoint.

---

### Phase 3: Hugo docs-site audit + corrections (~35%)

**Files:**
- `docs-site/content/web-app.md` — Modify
- `docs-site/content/dot-format.md` — Modify
- `docs-site/content/docker.md` — Modify (if still stale)
- (Optionally) `docs-site/content/cli.md`, `docs-site/content/rest-api.md` — Modify (only if discrepancies discovered during audit)

**Tasks:**
- [ ] `web-app.md`:
  - Document the Git History Panel (location in the project detail view, what it displays, and what degraded states look like).
  - Ensure system tools wording uses `graphviz` (not `dot`) when describing required tools.
- [ ] `dot-format.md`:
  - Extend the Node Types table to include missing shapes/types used by the engine:
    - `shape=component` — parallel fan-out node type (explicit)
    - `shape=tripleoctagon` — parallel fan-in / join semantics
    - `shape=parallelogram` — tool node
    - `shape=house` — stack manager loop
  - Clarify how “multiple outgoing edges” interacts with explicit parallel node types (avoid ambiguity).
- [ ] `docker.md`:
  - Ensure the base image description matches `Dockerfile.base` (Java 25 JRE, not Java 21).
  - Spot-check that listed included tools match the actual `apt-get install` list (keep it high-level if needed).
- [ ] Run a consistency pass across all Hugo pages for:
  - correct port (7070)
  - correct links (docs site, repo edit links)
  - correct terminology (projects, stages, settings)

---

### Phase 4: Fix `docs/api/rest-v1.md` and reconcile with router (~15%)

**Files:**
- `docs/api/rest-v1.md` — Modify

**Tasks:**
- [ ] Correct the default port to `7070`.
- [ ] Validate that the endpoint list matches `RestApiRouter.kt`:
  - Confirm presence and payload shape of `GET /api/v1/projects/{id}/git`.
  - Confirm documentation of `/api/v1/docs` (swagger UI) is accurate and does not conflict with the removed legacy `/docs`.
- [ ] Spot-check a small set of representative endpoints across categories (projects CRUD, artifacts, DOT ops, settings, models, SSE) to ensure request/response shapes and status codes match implementation patterns.

---

### Phase 5: Verification + build checks (~10%)

**Files:**
- (No additional changes required; this is validation.)

**Tasks:**
- [ ] Cross-check updated docs against code:
  - UI feature descriptions vs `WebMonitorServer.kt`
  - Endpoint descriptions vs `RestApiRouter.kt`
  - DOT node type mapping vs `CLAUDE.md` architecture section
  - Docker Java version vs `Dockerfile.base`
- [ ] Run Hugo build (build check only): `hugo --source docs-site`
- [ ] Ensure `docs-site/public/` remains uncommitted (gitignored).

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `README.md` | Modify | Remove stale `/docs` references; update endpoint counts, tool label, Java version language. |
| `docs-site/content/web-app.md` | Modify | Document Git History Panel; align system tools terminology (`graphviz`). |
| `docs-site/content/dot-format.md` | Modify | Add missing node types and clarify orchestration shapes. |
| `docs-site/content/docker.md` | Modify | Align Java version (25) and runtime/tool descriptions with `Dockerfile.base`. |
| `docs/api/rest-v1.md` | Modify | Correct default port; ensure endpoint list matches `RestApiRouter.kt`. |

## Definition of Done

- [ ] `README.md` contains no stale references to an in-app `/docs` page; documentation is clearly external (Hugo site).
- [ ] All docs consistently use `graphviz` (not `dot`) as the required system tool label where describing the UI/system tools grid.
- [ ] Default port is documented as `7070` everywhere.
- [ ] REST endpoint count language in `README.md` is accurate for current `RestApiRouter.kt`.
- [ ] Git History Panel is documented in `docs-site/content/web-app.md`, including degraded states and relationship to the REST endpoint.
- [ ] `docs-site/content/dot-format.md` includes the missing orchestration node types used by the engine.
- [ ] `docs/api/rest-v1.md` is consistent with current routing and does not mislead about removed legacy routes.
- [ ] `hugo --source docs-site` completes successfully.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Confusing `/docs` (legacy removed) with `/api/v1/docs` (swagger UI) | Medium | Medium | Explicitly distinguish “legacy runtime docs route removed” vs “REST swagger UI under `/api/v1/docs`”. |
| Docs drift again across three surfaces | Medium | Medium | Add minimal guidance in docs where overlap exists; clarify intended audience of each surface. |
| Over-scoping into code changes | Low | Medium | Keep sprint strictly doc-only; treat discrepancies as documentation fixes unless a true bug is discovered (log separately). |
| Advanced node types confuse new users | Medium | Low | Mark advanced shapes as “orchestration/advanced” and provide concise examples; keep default/primary path prominent. |

## Security Considerations

- Documentation should not encourage embedding API keys in command lines or committed files; prefer environment variables and `.env` patterns as already established.
- Ensure REST docs do not imply authentication where none exists; avoid overstating security guarantees.
- Avoid including any secrets in examples; keep placeholders.

## Dependencies

- Sprint 021–023 features are assumed present and are the subject of the doc updates.
- Hugo availability for local build checks (`hugo --source docs-site`).

## Open Questions

1. Should the additional node types (`component`, `tripleoctagon`, `parallelogram`, `house`) be documented in the user-facing DOT guide, or treated as advanced/internal? If documented, how prominently should they appear?

2. Should the legacy non-versioned browser API routes (`/api/projects`, `/api/run`, etc.) be documented anywhere, or intentionally left undocumented as SPA-internal implementation details?

3. Should `docs/api/rest-v1.md` and `docs-site/content/rest-api.md` be kept intentionally in sync, or should one be treated as canonical with the other deprecated over time?

