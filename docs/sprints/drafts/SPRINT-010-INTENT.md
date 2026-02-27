# Sprint 010 Intent: Full-Featured RESTful API

## Seed

Create a RESTful API for this application. It should contain all of the standard CRUD endpoints
along with endpoints for any other actions that can be taken for pipelines such as archive, rerun,
etc. It should also support export of pipelines, imports of pipelines, downloading of artifacts,
pause, resume, settings, anything that this application supports should be available. It should be
a full-featured API that conforms to standard RESTful philosophy.

## Context

Corey's Attractor is a pipeline orchestration system that uses DOT graph format to define pipelines
with LLM-powered stages. The existing server (WebMonitorServer.kt) serves both a browser UI and a
set of functional but non-RESTful API endpoints that the browser consumes. These endpoints are
tightly coupled to the browser's needs: they use verb-in-URL patterns (`/api/cancel`,
`/api/archive`), mix HTTP verbs inconsistently, and pass parameters as form-encoded bodies or
query strings without a coherent resource model.

The goal is a proper REST API that external clients (CLI tools, CI/CD integrations, external
orchestrators, other services) can use without needing to understand the browser-facing API's
quirks. This API should be self-contained, versioned, and follow standard RESTful conventions.

## Recent Sprint Context

- **Sprint 007** (Intelligent Failure Diagnosis): Added self-healing capabilities — `LlmFailureDiagnoser`,
  repair attempts, `failure_report.json` artifacts, and new pipeline events. Any pipeline state
  exposed by the REST API must include `hasFailureReport` and the failure report itself should be
  accessible.

- **Sprint 008** (Performance Optimization): Optimized SSE broadcast storm, eliminated filesystem
  probes from hot paths, improved log structures. The REST API SSE endpoint must respect the
  optimized broadcast strategy.

- **Sprint 009** (Critical Path Test Coverage): Established Kotest FunSpec + `io.kotest` matcher
  conventions for all test files. REST API tests must follow the same patterns.

## Relevant Codebase Areas

### Primary Target Files
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — All existing HTTP routing; the new REST
  API will live alongside or replace these routes. Very large (~2000+ lines), contains embedded
  HTML/CSS/JS for the browser UI.
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — Pipeline execution operations (submit,
  resubmit, pause, resume). REST API will delegate to these methods.
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` — Pipeline management (archive, delete,
  cancel, getAll, getOrHydrate). REST API will delegate here.
- `src/main/kotlin/attractor/web/PipelineState.kt` — State model; `toJson()` method produces
  the existing JSON format. REST API may expose the same format or a cleaner version.
- `src/main/kotlin/attractor/db/RunStore.kt` — SQLite persistence. Accessed via registry.

### Supporting Files
- `src/main/kotlin/attractor/Main.kt` — Entry point; creates server, registry, store
- `src/main/kotlin/attractor/state/ArtifactStore.kt` — Artifact management
- `src/main/kotlin/attractor/lint/Validator.kt` — DOT pipeline validation (linting)
- `src/main/kotlin/attractor/dot/Parser.kt` — DOT parsing
- `src/main/kotlin/attractor/llm/ModelCatalog.kt` — LLM model catalog

### Existing Endpoints (Non-RESTful, to be mapped)
| Existing Route | Method | Maps To |
|----------------|--------|---------|
| `/api/pipelines` | GET | `GET /api/v1/pipelines` |
| `/api/pipeline-view?id=` | GET | `GET /api/v1/pipelines/{id}` |
| `/api/run` | POST | `POST /api/v1/pipelines` |
| `/api/rerun` | POST | `POST /api/v1/pipelines/{id}/rerun` |
| `/api/cancel` | POST | `POST /api/v1/pipelines/{id}/cancel` |
| `/api/pause` | POST | `POST /api/v1/pipelines/{id}/pause` |
| `/api/resume` | POST | `POST /api/v1/pipelines/{id}/resume` |
| `/api/archive` | POST | `POST /api/v1/pipelines/{id}/archive` |
| `/api/unarchive` | POST | `POST /api/v1/pipelines/{id}/unarchive` |
| `/api/delete` | POST | `DELETE /api/v1/pipelines/{id}` |
| `/api/iterate` | POST | `POST /api/v1/pipelines/{id}/iterations` |
| `/api/pipeline-family?id=` | GET | `GET /api/v1/pipelines/{id}/family` |
| `/api/run-artifacts?id=` | GET | `GET /api/v1/pipelines/{id}/artifacts` |
| `/api/run-artifact-file?id=&path=` | GET | `GET /api/v1/pipelines/{id}/artifacts/{path}` |
| `/api/stage-log?id=&stage=` | GET | `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` |
| `/api/download-artifacts?id=` | GET | `GET /api/v1/pipelines/{id}/artifacts.zip` |
| `/api/export-run?id=` | GET | `GET /api/v1/pipelines/{id}/export` |
| `/api/import-run` | POST | `POST /api/v1/pipelines/import` |
| `/api/render` | POST | `POST /api/v1/dot/render` |
| `/api/generate` | POST | `POST /api/v1/dot/generate` |
| `/api/generate/stream` | POST | `GET /api/v1/dot/generate/stream` (SSE) |
| `/api/fix-dot` | POST | `POST /api/v1/dot/fix` |
| `/api/iterate/stream` | POST | `GET /api/v1/dot/iterate/stream` (SSE) |
| `/api/settings` | GET | `GET /api/v1/settings` |
| `/api/settings/update` | POST | `PUT /api/v1/settings/{key}` |
| `/events` | GET | `GET /api/v1/events` |

### New Endpoints (Not in existing API)
- `GET /api/v1/pipelines/{id}/failure-report` — Access failure_report.json directly
- `GET /api/v1/pipelines/{id}/stages` — List stages
- `PATCH /api/v1/pipelines/{id}` — Update pipeline metadata (dot source, display name)
- `POST /api/v1/dot/validate` — Lint/validate a DOT pipeline
- `GET /api/v1/models` — List available LLM models from catalog
- `GET /api/v1/events/{id}` — SSE stream filtered to a single pipeline
- `GET /api/v1/settings/{key}` — Get a single setting

## Constraints

- Must follow project conventions in CLAUDE.md (no CLAUDE.md found — use patterns from existing sprints)
- Must integrate with existing `WebMonitorServer.kt` without breaking the browser UI
- Must NOT remove or change existing browser-facing routes (backward compatible)
- Must use same HTTP server framework already in use (identify from code)
- Must follow Kotest FunSpec test conventions from Sprint 009
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- No new Gradle dependencies (reuse existing HTTP server library)
- No regressions to existing UI, SSE, artifacts, or pause/resume functionality

## Success Criteria

1. A complete API spec document describing all REST endpoints (URL, method, request/response shape)
2. All endpoints are implemented and accessible under `/api/v1/`
3. External clients can perform all pipeline operations without using the browser UI
4. API follows RESTful conventions: resource-based URLs, correct HTTP verbs, appropriate status codes
5. Streaming endpoints use SSE (consistent with existing streaming design)
6. Error responses are consistent JSON: `{"error": "message", "code": "ERROR_CODE"}`
7. All endpoints have integration tests following Kotest FunSpec conventions

## Verification Strategy

- Reference: Existing functional endpoints provide a behavioral spec — new REST endpoints must
  produce equivalent results to their existing counterparts
- Conformance: Each REST endpoint must be tested against the corresponding existing behavior
- Testing: Integration tests using `TestableWebMonitorServer` pattern (if it exists) or equivalent
  test HTTP client approach
- Edge cases: 404 for unknown ID, 409 for invalid state transitions (e.g., pause a completed
  pipeline), 400 for malformed requests

## Uncertainty Assessment

- **Correctness uncertainty**: Medium — REST conventions are well-known, but mapping the existing
  behavioral semantics correctly requires care (e.g., what does "iterate" create vs. "rerun")
- **Scope uncertainty**: Low-Medium — the existing endpoints define the feature surface; the main
  uncertainty is how to handle SSE streaming in a RESTful way
- **Architecture uncertainty**: High — the biggest question is whether to add a new REST handler
  file or refactor WebMonitorServer.kt; the former avoids regressions but duplicates routing code;
  the latter is cleaner but risky given the server's size

## Open Questions

1. **Architecture**: New file (`RestApiServer.kt` or `PipelineApiRouter.kt`) alongside
   `WebMonitorServer.kt`, or refactor `WebMonitorServer.kt` in-place?
2. **Versioning**: Should the API be `/api/v1/` from the start, or unversioned `/api/`? (keeping
   parity with existing `/api/` routes would avoid URL conflicts)
3. **SSE streaming for DOT generation/fix/iterate**: These currently use `POST` with SSE response,
   which is non-standard. REST convention would use `GET` with query params, or accept `POST`
   and return a stream. What's the preferred approach?
4. **Request format**: Should new REST endpoints accept JSON bodies, form-encoded bodies (like
   existing), or both?
5. **Response format for pipeline state**: Should `/api/v1/pipelines/{id}` return the same JSON
   as the existing `toJson()` format, or a cleaner/normalized representation?
6. **Authentication**: Out of scope for this sprint, or should we lay groundwork?
