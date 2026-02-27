# Sprint 010: Versioned REST API v1

## Overview

The current API surface in `WebMonitorServer.kt` is functionally complete for the browser, but it is
not shaped as a stable external contract. It mixes action-style paths (`/api/rerun`, `/api/archive`),
inconsistent verbs, and request formats tuned for UI internals instead of resource semantics.
External clients can use it, but only by reverse-engineering browser behavior.

This sprint introduces a first-class, versioned REST API under `/api/v1/` that exposes the full
pipeline capability set (CRUD + lifecycle actions + artifacts + import/export + DOT tooling +
settings + SSE). The design goal is additive and safe: keep all existing browser routes operational,
layer REST routes alongside them, and route both paths through the same underlying services
(`PipelineRunner`, `PipelineRegistry`, `RunStore`) so behavior remains consistent.

The outcome is a documented and tested API contract suitable for CLI tools, CI/CD automation,
third-party orchestration, and future auth/rate-limit work, while preserving the current UI without
regression.

## Use Cases

1. **Automated CI orchestration**: A CI job starts a pipeline, streams events, downloads artifacts,
   and archives results using only `/api/v1/*` endpoints.
2. **External run-management client**: A script lists runs, fetches one run, pauses/resumes, reruns,
   and inspects lineage (`family`) without relying on browser-specific payload quirks.
3. **Failure diagnosis consumption**: A tool can fetch `failure_report.json` directly and branch
   behavior on `hasFailureReport` and diagnosis metadata.
4. **Repository portability**: Export/import endpoints allow moving pipeline runs between instances
   with explicit conflict behavior.
5. **Programmatic DOT workflow**: Clients validate, render, generate, fix, and iterate DOT via
   consistent JSON request/response envelopes and SSE streams.
6. **Ops introspection**: Settings and available model catalog are discoverable through stable,
   versioned endpoints.

## Architecture

### Route Strategy

```text
HTTP Request
  -> WebMonitorServer route registration
      -> Legacy route (/api/* existing) OR REST route (/api/v1/*)
          -> shared helper/service layer
              -> PipelineRunner / PipelineRegistry / RunStore / Validator / Parser / ModelCatalog
                  -> JSON response envelope + status code mapping
```

### Resource Model

```text
/pipelines                 collection of runs
/pipelines/{id}            single run
/pipelines/{id}/artifacts  run artifacts collection
/pipelines/{id}/stages     stage metadata collection
/settings                  settings collection
/settings/{key}            single setting
/dot/*                     DOT utility resources (render/validate/generate/fix/iterate)
/events                    event stream resources
/models                    model catalog
```

### Contract Principles

- Versioned namespace: all new routes under `/api/v1`.
- Backward compatibility: existing `/api/*` and `/events` remain unchanged.
- JSON bodies for REST writes (`POST`/`PUT`/`PATCH`), query/path params for reads.
- Uniform error envelope: `{"error":"message","code":"ERROR_CODE"}`.
- SSE remains streaming transport for event and generation/iteration streams.
- REST routes call the same underlying methods as legacy routes to prevent semantic drift.

### Proposed Endpoint Matrix (v1)

```text
GET    /api/v1/pipelines
POST   /api/v1/pipelines
GET    /api/v1/pipelines/{id}
PATCH  /api/v1/pipelines/{id}
DELETE /api/v1/pipelines/{id}

POST   /api/v1/pipelines/{id}/rerun
POST   /api/v1/pipelines/{id}/cancel
POST   /api/v1/pipelines/{id}/pause
POST   /api/v1/pipelines/{id}/resume
POST   /api/v1/pipelines/{id}/archive
POST   /api/v1/pipelines/{id}/unarchive
POST   /api/v1/pipelines/{id}/iterations
GET    /api/v1/pipelines/{id}/family

GET    /api/v1/pipelines/{id}/artifacts
GET    /api/v1/pipelines/{id}/artifacts/{path}
GET    /api/v1/pipelines/{id}/artifacts.zip
GET    /api/v1/pipelines/{id}/export
GET    /api/v1/pipelines/{id}/failure-report
GET    /api/v1/pipelines/{id}/stages
GET    /api/v1/pipelines/{id}/stages/{nodeId}/log

POST   /api/v1/pipelines/import

POST   /api/v1/dot/render
POST   /api/v1/dot/validate
POST   /api/v1/dot/generate
GET    /api/v1/dot/generate/stream
POST   /api/v1/dot/fix
POST   /api/v1/dot/iterate
GET    /api/v1/dot/iterate/stream

GET    /api/v1/settings
GET    /api/v1/settings/{key}
PUT    /api/v1/settings/{key}

GET    /api/v1/models
GET    /api/v1/events
GET    /api/v1/events/{id}
```

## Implementation Plan

### Phase 1: API Contract and Shared HTTP Helpers (~15%)

**Files:**
- `docs/api/rest-v1.md` (create)
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (modify)

**Tasks:**
- [ ] Author REST contract doc with endpoint, method, request/response examples, and error codes.
- [ ] Add centralized helpers for JSON body parsing, path/query extraction, response writing, and
      uniform error envelope.
- [ ] Define status-code mapping guidelines (400 malformed input, 404 missing run, 409 invalid
      state transition, 500 internal error).
- [ ] Keep helper usage additive so legacy routes can remain unchanged initially.

### Phase 2: Pipelines Collection and Item Endpoints (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (modify)
- `src/main/kotlin/attractor/web/PipelineRunner.kt` (modify if small helper needed)
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` (modify if state-transition metadata needed)
- `src/main/kotlin/attractor/web/PipelineState.kt` (modify for PATCH metadata support if needed)

**Tasks:**
- [ ] Implement `GET /api/v1/pipelines` and `GET /api/v1/pipelines/{id}` with stable JSON schema.
- [ ] Implement `POST /api/v1/pipelines` for run submission (JSON body).
- [ ] Implement `PATCH /api/v1/pipelines/{id}` for mutable metadata (display name / dot source)
      with validation and conflict handling.
- [ ] Implement `DELETE /api/v1/pipelines/{id}` mapped to existing delete behavior.
- [ ] Ensure pipeline payload includes failure-report presence signal (`hasFailureReport`).

### Phase 3: Lifecycle Actions and Lineage (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (modify)
- `src/main/kotlin/attractor/web/PipelineRunner.kt` (modify)
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` (modify)

**Tasks:**
- [ ] Implement lifecycle action routes (`rerun`, `cancel`, `pause`, `resume`, `archive`, `unarchive`).
- [ ] Implement `POST /api/v1/pipelines/{id}/iterations` mapped to existing iterate semantics.
- [ ] Implement `GET /api/v1/pipelines/{id}/family` with existing family output.
- [ ] Return 409 for invalid transitions (e.g., pause terminal run), not generic 200/500.

### Phase 4: Artifacts, Stage Logs, Export/Import (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (modify)
- `src/main/kotlin/attractor/state/ArtifactStore.kt` (modify if path sanitization helper extraction is needed)
- `src/main/kotlin/attractor/db/RunStore.kt` (modify only if import/export metadata persistence gaps appear)

**Tasks:**
- [ ] Implement artifact listing and single artifact fetch with safe relative-path handling.
- [ ] Implement artifacts ZIP download endpoint under v1 namespace.
- [ ] Implement stage listing and stage log read endpoint.
- [ ] Implement run export and import endpoints under `/api/v1/pipelines/...`.
- [ ] Add `GET /api/v1/pipelines/{id}/failure-report` to expose failure artifact directly.

### Phase 5: DOT Tooling, Settings, Models, SSE (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (modify)
- `src/main/kotlin/attractor/lint/Validator.kt` (reuse)
- `src/main/kotlin/attractor/dot/Parser.kt` (reuse)
- `src/main/kotlin/attractor/llm/ModelCatalog.kt` (reuse)

**Tasks:**
- [ ] Implement `POST /api/v1/dot/validate` using existing validation path.
- [ ] Implement v1 routes for render/generate/fix/iterate (sync + stream variants).
- [ ] Implement `GET /api/v1/settings`, `GET /api/v1/settings/{key}`, `PUT /api/v1/settings/{key}`.
- [ ] Implement `GET /api/v1/models` from model catalog.
- [ ] Implement `GET /api/v1/events` and `GET /api/v1/events/{id}` with existing optimized SSE
      broadcast strategy from Sprint 008 (no per-client bespoke heavy recompute).

### Phase 6: Integration Tests and Parity Verification (~10%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerRestApiTest.kt` (create)
- `src/test/kotlin/attractor/web/WebMonitorServerSseTest.kt` (create)
- `src/test/kotlin/attractor/web/PipelineStateTest.kt` (modify only if schema assertions are added)

**Tasks:**
- [ ] Add integration tests for all v1 endpoint families (happy paths + key failures).
- [ ] Add parity tests comparing representative legacy vs v1 responses for same run state.
- [ ] Add SSE tests for `/api/v1/events` and pipeline-filtered stream endpoint.
- [ ] Validate consistent error envelope across malformed JSON, missing IDs, and invalid transitions.
- [ ] Run full build/test command from intent constraints and confirm success.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `docs/api/rest-v1.md` | Create | Canonical REST API v1 specification and examples |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Register and implement `/api/v1/*` routes + shared HTTP helpers |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Reusable lifecycle/action helpers for REST parity |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Resource-level operations and transition-aware outcomes |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Modify | Optional metadata mutation/read shaping for REST item endpoints |
| `src/main/kotlin/attractor/state/ArtifactStore.kt` | Modify (optional/minimal) | Safe artifact path handling helper reuse |
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify (optional/minimal) | Import/export persistence parity fixes if needed |
| `src/test/kotlin/attractor/web/WebMonitorServerRestApiTest.kt` | Create | Endpoint integration coverage for `/api/v1/*` |
| `src/test/kotlin/attractor/web/WebMonitorServerSseTest.kt` | Create | SSE contract and filtering behavior coverage |
| `src/test/kotlin/attractor/web/PipelineStateTest.kt` | Modify (optional) | Shared JSON field assertions (`hasFailureReport`, stage metadata) |

## Definition of Done

- [ ] `docs/api/rest-v1.md` exists and documents every `/api/v1/*` endpoint with request/response schema.
- [ ] All existing browser-facing routes continue working unchanged.
- [ ] Complete REST surface is available under `/api/v1/*` for pipeline CRUD and lifecycle actions.
- [ ] Artifacts, export/import, stage logs, and failure report are accessible via REST routes.
- [ ] DOT tooling routes include validate/render/generate/fix/iterate with stream parity where applicable.
- [ ] Settings and model-catalog endpoints are available under `/api/v1/*`.
- [ ] SSE endpoints exist for all-pipelines and single-pipeline streams under `/api/v1/events*`.
- [ ] Error responses are uniformly shaped as `{"error":"message","code":"ERROR_CODE"}`.
- [ ] Integration tests cover success and failure paths across endpoint families.
- [ ] Build/test command from intent passes without regressions.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Route duplication in `WebMonitorServer.kt` becomes hard to maintain | Medium | High | Extract shared request/response helpers and operation delegates before expanding route count |
| Inconsistent semantics between legacy and v1 routes | Medium | High | Add parity tests for key operations and keep both paths delegating to identical service methods |
| SSE filtered endpoint introduces per-client heavy work | Medium | Medium | Reuse shared broadcast payload; filter lightweight by pipeline ID without recomputing full state |
| Import/export route handling can regress ZIP safety | Low | High | Reuse existing zip/path guards and add explicit tests for invalid archive content |
| `PATCH /pipelines/{id}` scope creep into unsupported mutations | Medium | Medium | Restrict writable fields explicitly and return 400 for unknown/immutable fields |
| Response schema churn affects clients | Medium | Medium | Lock schema in `docs/api/rest-v1.md` and enforce with integration assertions |

## Security Considerations

- Validate and sanitize all path-derived artifact access to prevent traversal.
- Keep ZIP import extraction constrained to temp directories with canonical path checks.
- Preserve current unauthenticated behavior (auth out of scope), but structure v1 routes so auth
  middleware can be inserted in a future sprint.
- Avoid returning stack traces or raw exception strings in API errors.

## Dependencies

- Sprint 007: failure diagnosis artifacts and `hasFailureReport` semantics.
- Sprint 008: optimized SSE broadcast behavior to preserve in `/api/v1/events*`.
- Sprint 009: Kotest FunSpec conventions for all new test files.
- No new Gradle dependencies.

## Open Questions

1. Should `PATCH /api/v1/pipelines/{id}` allow updating only `displayName`, or both `displayName`
   and `dotSource` in Sprint 010?
2. Should stream-generation/iterate routes remain `POST`-with-stream for compatibility, or should
   v1 expose `GET` stream endpoints only and keep request payload via query token/reference?
3. Do we want to expose a top-level `/api/v1/health` endpoint in this sprint, or defer to a later
   operational-hardening sprint?
