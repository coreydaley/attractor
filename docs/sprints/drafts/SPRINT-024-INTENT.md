# Sprint 024 Intent: Documentation Audit and Update

## Seed

Ensure that all documentation in the readme and the docs-site is up-to-date with all of the changes that we have made and all features that are currently available in this product.

## Context

Attractor has had 23 completed sprints. Sprints 021–023 introduced significant new features and infrastructure changes that were not fully reflected in the documentation:

- **Sprint 021** added a Git History Panel to the project detail tab in the web UI, plus a new `GET /api/v1/projects/{id}/git` REST endpoint
- **Sprint 022** migrated docs out of the running server to a Hugo static site (`docs-site/`), removed the in-app `/docs` endpoint entirely, and deployed to GitHub Pages
- **Sprint 023** split the Docker build into a base image (`attractor-base`) and a server image
- **Recent chores** upgraded the project to Java 25, Kotlin 2.3.10, and Gradle 9.4.0

Documentation in `README.md` still contains stale references to the in-app `/docs` endpoint and the old four-tab layout. The `docs-site/content/web-app.md` does not document the Git History Panel. The `docs/api/rest-v1.md` file has a wrong default port. The system-tools reference in both README and docs-site uses `dot` while the UI now says `graphviz`. Several minor inconsistencies exist throughout.

## Recent Sprint Context

- **Sprint 021** — Project Git History Panel: Added `WorkspaceGit.summary()`, REST endpoint `GET /api/v1/projects/{id}/git`, and a collapsible git bar in the project detail tab showing branch, commit count, last commit message, and an expandable commit log. Auto-refreshes 500ms after terminal SSE events.

- **Sprint 022** — Hugo Docs Microsite on GitHub Pages: Extracted all in-app docs to `docs-site/` (five Markdown pages), deployed via GitHub Actions to `https://coreydaley.github.io/attractor/`. Removed the `/docs` route, `docsHtml()`, and all seven private docs functions from `WebMonitorServer.kt`.

- **Sprint 023** — Split Docker Build into Base and Server Images: Introduced `Dockerfile.base` (JRE + OS tools), simplified `Dockerfile` to copy a pre-built JAR, and restructured `release.yml` into `build-base` + `build-and-release` jobs. Documented in `docs-site/content/docker.md`.

## Relevant Codebase Areas

- `README.md` — primary user-facing readme
- `docs-site/content/web-app.md` — web UI documentation (Hugo)
- `docs-site/content/rest-api.md` — REST API reference (Hugo)
- `docs-site/content/cli.md` — CLI reference (Hugo)
- `docs-site/content/dot-format.md` — DOT format guide (Hugo)
- `docs-site/content/docker.md` — Docker deployment guide (Hugo)
- `docs/api/rest-v1.md` — machine/developer REST API reference
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — source of truth for REST endpoints
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — source of truth for UI features

## Identified Gaps

### README.md
1. **Stale in-app docs paragraph** (line 222): "Click **Docs** in the navigation bar to open the built-in documentation window...organized into four tabs...served directly by the Attractor server" — the `/docs` endpoint was removed in Sprint 022; docs are now an external Hugo site opened in a new tab
2. **`/docs` in API table** (line 308): Lists `GET /docs | Built-in documentation window` — this route no longer exists; the nav button now opens the external Hugo site
3. **"built-in Docs window" reference** (line 335): Should reference the documentation site, not a built-in window
4. **Stale endpoint count**: "35-endpoint" and "35 endpoints" appear in multiple places; actual count is now 37+
5. **`dot` → `graphviz` in system tools warning** (line 164): Should match the UI label change we just made
6. **WebMonitorServer.kt description in project structure** (line 496-497): Still mentions "/docs endpoint"
7. **JDK description in Make Options table**: "Path to JDK 21" should say "Path to JDK 25"

### docs-site/content/web-app.md
1. **Git History Panel missing**: Sprint 021 added a collapsible git bar to the project detail tab — not mentioned anywhere in the docs
2. **`dot` → `graphviz` in System Tools** (line 200): "Required tools (`java`, `git`, `dot`)" should say `graphviz`

### docs-site/content/dot-format.md
1. **Missing node types**: Architecture doc lists `component` (parallel fan-out), `tripleoctagon` (parallel fan-in), `parallelogram` (tool node), and `house` (stack manager loop) — none of these appear in the Node Types table

### docs/api/rest-v1.md
1. **Wrong default port**: "Default port: 8080" — should be 7070
2. **Git endpoint**: Already documented (endpoint #37) — verify it matches the implementation

### docs-site/content/rest-api.md
- Git endpoint already documented — looks complete

## Constraints

- Must follow project conventions in CLAUDE.md
- Documentation only — no code changes
- Hugo site lives in `docs-site/content/`; `docs-site/public/` is gitignored and should NOT be committed
- The `docs/api/rest-v1.md` is a developer/machine reference (separate from the Hugo user-facing docs)
- Verify changes against actual source code in `RestApiRouter.kt`, `WebMonitorServer.kt`, and the CLAUDE.md architecture section before writing

## Success Criteria

- All stale references to the in-app `/docs` endpoint are removed from README.md
- System tool label `dot` updated to `graphviz` everywhere in docs
- README.md endpoint count is accurate
- Git History Panel documented in `docs-site/content/web-app.md`
- Additional node types documented in `docs-site/content/dot-format.md`
- `docs/api/rest-v1.md` default port corrected to 7070
- No other known inaccuracies remain

## Verification Strategy

- Diff each file against actual code in `WebMonitorServer.kt` and `RestApiRouter.kt` to confirm accuracy
- Cross-check REST endpoint list against `RestApiRouter.kt` routing table
- Validate node type shapes against `CLAUDE.md` architecture section
- Ensure Hugo site still builds: `hugo --source docs-site` (build check only, not deploy)

## Uncertainty Assessment

- **Correctness uncertainty**: Low — the gaps are clearly identified from source inspection
- **Scope uncertainty**: Low — documentation-only changes to specific, identified files
- **Architecture uncertainty**: Low — no code changes, no new architecture decisions

## Open Questions

1. Should the additional node types (`component`, `tripleoctagon`, `parallelogram`, `house`) be documented at all in the user-facing docs, or are they internal/advanced? Their shapes map to orchestration features (parallel fan-out/fan-in, tool nodes, stack manager loops) that do appear in the DOT format used by users.

2. Should the legacy non-versioned browser API routes (`/api/projects`, `/api/run`, etc.) be documented anywhere, or are they intentionally undocumented because they're internal to the SPA?

3. Should `docs/api/rest-v1.md` and `docs-site/content/rest-api.md` be kept in sync, or is the former being deprecated in favor of the Hugo site?
