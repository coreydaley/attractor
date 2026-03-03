# Sprint 015: Test Coverage Closure for Web App, REST API, and CLI

## Overview

The codebase now has three stable user-facing surfaces with meaningful behavior: the browser app (`WebMonitorServer`), the REST API (`RestApiRouter`), and the Kotlin CLI (`attractor.cli`). Feature velocity has outpaced test depth in each of those layers. We currently have strong foundation tests (state engine, parser/linting, SSE basics, selected REST and CLI verbs), but coverage is uneven around route edges, binary endpoints, spec endpoints, and several CLI command verbs.

This sprint is a focused coverage closure sprint. It does not add product features. It tightens confidence by adding hermetic tests for uncovered commands/routes and for high-value boundary behavior (method guards, required params, status code envelopes, binary responses). The emphasis is pragmatic: one solid test per command/route behavior class rather than exhaustive combinatorics.

The implementation stays inside existing patterns:
- Kotest `FunSpec`
- real `HttpServer` on ephemeral port `0` for router/server integration tests
- fake in-memory JDK `HttpServer` for CLI method/path assertions
- temp DB/files/directories for hermetic isolation
- no new dependencies and no external LLM calls

## Use Cases

1. **Safe refactoring**: a route refactor in `RestApiRouter.kt` does not silently break less-trafficked endpoints like `/pipelines/{id}/dot` or spec routes.
2. **CLI contract confidence**: modifying `PipelineCommands` or `DotCommands` preserves exact HTTP method/path/body behavior across all verbs.
3. **Web legacy API safety**: browser-specific `/api/*` routes keep expected method/param contracts while the SPA evolves.
4. **Release confidence**: CI failure points to concrete behavioral regressions rather than downstream manual QA discovery.

## Architecture

```text
Test Coverage Layers

1) REST API integration tests (real RestApiRouter)
   - File: RestApiRouterTest.kt (expand)
   - File: RestApiSpecRoutesTest.kt (new)

2) CLI command dispatch tests (fake HTTP server)
   - File: PipelineCommandsTest.kt (expand)
   - File: ArtifactCommandsTest.kt (expand)
   - File: DotCommandsTest.kt (expand)
   - File: ModelsCommandTest.kt (new)
   - File: EventsCommandTest.kt (new)
   - File: MainTest.kt (expand for --version)

3) Browser API smoke/boundary tests (real WebMonitorServer)
   - File: WebMonitorServerBrowserApiTest.kt (new)

Coverage policy
- Non-LLM endpoints: at least one happy-path or documented error-path test each.
- LLM-dependent endpoints: parameter validation and route contract tests only (typically 400 BAD_REQUEST).
- Binary endpoints: assert status + content type + basic payload shape (e.g., ZIP magic `PK`).
```

## Implementation Plan

### Phase 1: REST API Coverage Expansion (~30%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify
- `src/test/kotlin/attractor/web/RestApiSpecRoutesTest.kt` — Create

**Tasks:**
- [ ] Add missing pipeline lifecycle/versioning route coverage in `RestApiRouterTest`:
  - `POST /api/v1/pipelines` success case returns `201` and includes `id`/`status`
  - `POST /api/v1/pipelines/{id}/rerun` (known id, idle state) returns documented invalid-state response
  - `GET /api/v1/pipelines/{id}/stages` returns array payload
  - `GET /api/v1/pipelines/{id}/dot` returns `text/plain` with DOT source
- [ ] Add artifacts/import-export route coverage:
  - `GET /api/v1/pipelines/{id}/artifacts.zip` returns ZIP payload (`PK` prefix)
  - `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` not-found case
  - `GET /api/v1/pipelines/{id}/export` returns ZIP payload + content type
  - `POST /api/v1/pipelines/import` invalid ZIP and missing `pipeline-meta.json` boundaries
  - `POST /api/v1/pipelines/dot` success and missing-body validation
- [ ] Add LLM-route validation-only tests (no live model dependency):
  - `POST /api/v1/dot/generate` missing `prompt` -> `400 BAD_REQUEST`
  - `POST /api/v1/dot/render` missing `dotSource` -> `400 BAD_REQUEST`
  - `POST /api/v1/dot/fix` missing `dotSource` -> `400 BAD_REQUEST`
  - `POST /api/v1/dot/iterate` missing `baseDot`/`changes` -> `400 BAD_REQUEST`
- [ ] Create `RestApiSpecRoutesTest.kt` for spec endpoints:
  - `GET /api/v1/openapi.json`
  - `GET /api/v1/openapi.yaml`
  - `GET /api/v1/swagger.json`
  - Assert expected status codes and content-type characteristics.

---

### Phase 2: CLI Pipeline + Artifact Command Gaps (~25%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` — Modify
- `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` — Modify

**Tasks:**
- [ ] Add pipeline command coverage for currently untested verbs:
  - `delete`, `rerun`, `resume`, `cancel`, `archive`, `unarchive`, `iterate`
- [ ] For each added pipeline verb test, assert:
  - request HTTP method
  - request URI path
  - key output marker (where applicable)
- [ ] Add boundary tests:
  - `pipeline iterate <id>` without `--file` returns `CliException(exitCode=2)`
  - lifecycle verbs missing `<id>` return `CliException(exitCode=2)`
- [ ] Add artifact command coverage for untested verbs:
  - `artifact get`, `artifact stage-log`, `artifact failure-report`, `artifact export`
- [ ] Add artifact argument-boundary tests:
  - `artifact get` missing args -> exit code `2`
  - `artifact stage-log` missing args -> exit code `2`

---

### Phase 3: CLI DOT + Models + Events + Main Gaps (~20%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` — Modify
- `src/test/kotlin/attractor/cli/commands/ModelsCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/MainTest.kt` — Modify

**Tasks:**
- [ ] Add DOT verb tests for missing command surface:
  - `dot render`, `dot fix`, `dot fix-stream`, `dot iterate`, `dot iterate-stream`
- [ ] For streaming verbs, use fake SSE responses and assert expected token/done handling output.
- [ ] Add DOT argument validation tests for usage errors (exit code `2`).
- [ ] Create `ModelsCommandTest.kt`:
  - `models list` table output case
  - `models` default-to-list behavior
  - `models --help`
  - unknown verb exit code `2`
- [ ] Create `EventsCommandTest.kt`:
  - `events` targets `/api/v1/events`
  - `events <id>` targets `/api/v1/events/{id}`
  - stream lines printed from SSE data
- [ ] Add `--version` coverage in `MainTest.kt` ensuring command exits normally and prints `attractor-cli` prefix.

---

### Phase 4: Web Browser API Route Coverage (~15%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Create

**Tasks:**
- [ ] Add smoke and boundary tests for browser API routes hosted by `WebMonitorServer`:
  - `GET /api/pipelines` -> `200`
  - `GET /api/pipeline-view` missing `id` -> `400`
  - `GET /api/pipeline-family` missing `id` -> `400`
  - `GET /api/run-artifacts` missing `id` -> `400`
  - `GET /api/stage-log` missing params -> `400`
  - `GET /api/settings` -> `200`
  - `GET /api/settings/cli-status` -> `200`
- [ ] Add method-guard tests for POST-only routes:
  - `GET /api/run`, `/api/rerun`, `/api/cancel`, `/api/pause`, `/api/resume`, `/api/archive`, `/api/unarchive`, `/api/delete` -> `405`
- [ ] Keep regression guard assertions:
  - `GET /` -> `200`
  - `GET /docs` -> `200`
  - `GET /api/v1/pipelines` -> `200`

---

### Phase 5: CI Verification + Coverage Inventory Update (~10%)

**Files:**
- `docs/sprints/drafts/SPRINT-015-CODEX-DRAFT.md` — (planning artifact, current file)
- test files above — no production code changes expected

**Tasks:**
- [ ] Run full test suite and verify no regressions.
- [ ] If flaky behavior appears in SSE/binary tests, harden with deterministic fake-server responses and explicit connection close semantics.
- [ ] Produce a brief endpoint/verb checklist in PR notes confirming each previously missing item is now covered.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | Add missing REST endpoint coverage (CRUD/lifecycle/artifacts/import-export/dot upload/validation boundaries) |
| `src/test/kotlin/attractor/web/RestApiSpecRoutesTest.kt` | Create | Isolate OpenAPI/Swagger endpoint route contract assertions |
| `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` | Modify | Cover missing pipeline verbs + usage error boundaries |
| `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` | Modify | Cover missing artifact verbs + argument validation |
| `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` | Modify | Cover missing dot verbs + streaming behavior + missing-arg boundaries |
| `src/test/kotlin/attractor/cli/commands/ModelsCommandTest.kt` | Create | Add coverage for models command class |
| `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` | Create | Add coverage for events command class |
| `src/test/kotlin/attractor/cli/MainTest.kt` | Modify | Add `--version` behavior coverage |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Create | Add legacy browser `/api/*` route smoke and method/param boundary tests |

## Definition of Done

### REST API
- [ ] Every non-LLM REST endpoint currently lacking tests has at least one passing test.
- [ ] LLM-dependent REST routes are covered at validation boundary level (`400 BAD_REQUEST` cases).
- [ ] Spec routes (`openapi.json`, `openapi.yaml`, `swagger.json`) have explicit route-contract tests.

### CLI
- [ ] Every CLI command verb has at least one test asserting expected HTTP path/method or stream target.
- [ ] `ModelsCommand` and `EventsCommand` have dedicated tests.
- [ ] `--version` flag behavior is tested.

### Web App
- [ ] Browser API `/api/*` routes have basic route/method/param smoke coverage.
- [ ] Existing docs/root/REST regression checks remain passing.

### Quality
- [ ] No new dependencies added.
- [ ] All tests remain hermetic (port `0`, temp files/dirs, teardown in `afterSpec`).
- [ ] Full test suite passes in CI and locally.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Endpoint behavior assumptions differ from actual implementation (status or payload shape) | Medium | Medium | Read handler code first; assert documented/actual contract explicitly in each new test |
| SSE tests hang waiting for stream termination | Medium | Medium | Use fake servers that send fixed SSE frames then close response body deterministically |
| Binary endpoint tests become brittle due to file-system state | Low | Medium | Use temp directories and minimal fixture generation; assert only stable markers (content type, ZIP magic bytes) |
| Over-testing LLM-dependent behavior introduces non-hermetic failures | Low | High | Restrict LLM route tests to validation and routing boundaries only |

## Security Considerations

- Tests stay local-only and ephemeral (no external network dependencies).
- No production credentials or API keys are required for sprint scope.
- Import/export tests should use synthetic ZIP fixtures only and validate expected error handling for malformed input.

## Dependencies

- Sprint 012: CLI command surface exists and is testable.
- Sprint 013: `/docs` endpoint and docs content tests already established.
- Sprint 014: Create-page upload tests already in place; this sprint extends browser API coverage without modifying upload behavior.

## Open Questions

1. Should spec route tests assert strict content type values or allow compatible variants (`application/json` vs charset-appended forms)?
2. Should `--version` test assert exact value formatting when manifest version is absent (`unknown`) or only assert prefix and non-error behavior?
3. For browser API coverage, do we want one test per route or table-driven tests to reduce boilerplate while keeping failure messages clear?
