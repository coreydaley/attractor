# Sprint 015: Comprehensive Test Coverage

## Overview

Fourteen sprints of feature development have built a mature, three-surface application: a browser SPA served by `WebMonitorServer`, a 35-endpoint REST API under `RestApiRouter`, and a full-featured Kotlin CLI client. Each surface ships with tests, but coverage has naturally grown uneven as features outpaced test authoring. A gap audit reveals that roughly half the REST API endpoints are untested, a dozen CLI command verbs have no test at all, two entire CLI command classes have no test file, and the legacy browser API routes in `WebMonitorServer` have only markup-presence smoke tests.

This sprint closes those gaps methodically and without complexity theater. Every new test follows the hermetic patterns already established in the codebase — ephemeral port-0 JDK `HttpServer` fake servers for CLI tests, and a real `WebMonitorServer` or `RestApiRouter` on port 0 for integration tests. No new test libraries, no new Gradle dependencies. The target is "adequate coverage": every non-LLM route has at least one passing test for either its happy path or its documented error contract, and every CLI verb has at least one test verifying the correct HTTP method and path are dispatched.

LLM-dependent endpoints (`POST /dot/generate`, `POST /dot/fix`, `POST /dot/iterate`, `GET /dot/*/stream`, and the browser `/api/generate` family) cannot be tested without a live model. These are tested at the parameter-validation layer only — a missing required field must return 400 with a `BAD_REQUEST` code. This is still valuable: it confirms routing works and guards against regressions in request-body parsing.

## Use Cases

1. **CI regression guard**: A developer adds a new route to `RestApiRouter`. The test suite catches any accidental breakage in the existing 35 endpoints because every route is now covered.

2. **CLI command confidence**: A contributor changes the HTTP path for `pipeline iterate` in `PipelineCommands.kt`. The new `pipeline iterate` test catches the regression immediately.

3. **Models and Events coverage**: Someone extends `ModelsCommand` or `EventsCommand`. The new test files (`ModelsCommandTest`, `EventsCommandTest`) provide a stable base to add assertions without starting from scratch.

4. **Browser API smoke**: A refactor to `WebMonitorServer` accidentally breaks `/api/pipelines`. The new `WebMonitorServerBrowserApiTest` catches the 500 before it reaches users.

5. **Spec endpoint verification**: A developer integrating with the OpenAPI spec checks that `GET /api/v1/openapi.json` returns 200 with JSON content. The spec route tests confirm this.

## Architecture

```
Test layers and their test files:

┌─────────────────────────────────────────────────────────────────┐
│  WebMonitorServer (port 0 real server)                          │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ WebMonitorServerBrowserApiTest (NEW)                     │   │
│  │  - Smoke tests for all /api/* browser routes             │   │
│  │  - Verifies method rejection (405), not-found (404)       │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ DocsEndpointTest (existing) + CreateDotUploadTest (exist)│   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  RestApiRouter (RestApiRouterTest — EXPAND + RestApiSpecTest NEW)│
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ RestApiRouterTest (existing + new cases)                 │   │
│  │  + create pipeline success, rerun, stages, stage-log,   │   │
│  │    artifacts.zip, export, import, dot-upload, dot/render │   │
│  │    400 cases, openapi/swagger routes                     │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  CLI (fake JDK HttpServer)                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ PipelineCommandsTest (existing + new verbs)              │   │
│  │  + delete, rerun, resume, cancel, archive, unarchive,   │   │
│  │    iterate                                               │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ ArtifactCommandsTest (existing + new verbs)              │   │
│  │  + get, stage-log, failure-report, export               │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ DotCommandsTest (existing + new verbs)                   │   │
│  │  + render, fix, fix-stream, iterate, iterate-stream     │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ ModelsCommandTest (NEW)                                  │   │
│  │  + list, --help, unknown verb                           │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ EventsCommandTest (NEW)                                  │   │
│  │  + stream all, stream by id, --help                     │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ MainTest (existing + --version)                          │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## Implementation Plan

### Phase 1: REST API Router — Gap Coverage (~25%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify (add new test cases)

**Tasks:**

_Pipeline CRUD additions:_
- [ ] `POST /api/v1/pipelines` with valid dotSource → 201 with `id` field (register succeeds; runner is not started in test context because `onUpdate` is a no-op)
- [ ] `GET /api/v1/pipelines/{id}/stages` → 200 with `stages` array (register a pipeline; stages should be empty list initially)
- [ ] `POST /api/v1/pipelines/{id}/rerun` → 200 or 409 depending on state (idle pipelines return 409 `INVALID_STATE` — same pattern as pause/resume/cancel)

_Artifact additions:_
- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` with no logsRoot → 200 with empty ZIP or 404 (read existing handler to determine which; document test)
- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` with no log file → 404 `NOT_FOUND`
- [ ] `GET /api/v1/pipelines/{id}/export` → 200 with ZIP content-type (`application/zip`)

_Import:_
- [ ] `POST /api/v1/pipelines/import` with empty body → 400 or handles gracefully (test the boundary)

_DOT upload:_
- [ ] `POST /api/v1/pipelines/dot` with valid DOT body → 200 or 400 (test the route exists and returns expected status)

_Download pipeline DOT:_
- [ ] `GET /api/v1/pipelines/{id}/dot` → 200 with `text/plain` content-type for registered pipeline

_DOT parameter validation (LLM boundary):_
- [ ] `POST /api/v1/dot/generate` with missing `prompt` field → 400 `BAD_REQUEST`
- [ ] `POST /api/v1/dot/render` with missing `dotSource` field → 400 `BAD_REQUEST`
- [ ] `POST /api/v1/dot/fix` with missing `dotSource` field → 400 `BAD_REQUEST`
- [ ] `POST /api/v1/dot/iterate` with missing `baseDot` or `changes` field → 400 `BAD_REQUEST`

_Spec routes:_
- [ ] `GET /api/v1/openapi.json` → 200 with `application/json` content-type
- [ ] `GET /api/v1/openapi.yaml` → 200 with `text/yaml` or `application/yaml` content-type
- [ ] `GET /api/v1/swagger.json` → 200 (swagger UI redirect or JSON)

---

### Phase 2: CLI — Pipeline Command Gap Coverage (~20%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` — Modify (add new test cases)

**Tasks:**
- [ ] `pipeline delete <id>` → sends `DELETE` to `/api/v1/pipelines/{id}`; prints `deleted: true`
- [ ] `pipeline rerun <id>` → sends `POST` to `/api/v1/pipelines/{id}/rerun`
- [ ] `pipeline resume <id>` → sends `POST` to `/api/v1/pipelines/{id}/resume`
- [ ] `pipeline cancel <id>` → sends `POST` to `/api/v1/pipelines/{id}/cancel`; prints `cancelled: true`
- [ ] `pipeline archive <id>` → sends `POST` to `/api/v1/pipelines/{id}/archive`; prints `archived: true`
- [ ] `pipeline unarchive <id>` → sends `POST` to `/api/v1/pipelines/{id}/unarchive`; prints `unarchived: true`
- [ ] `pipeline iterate <id> --file <path>` → sends `POST` to `/api/v1/pipelines/{id}/iterations` with dotSource from file; prints result
- [ ] `pipeline iterate <id>` without `--file` → throws `CliException` exit code 2

---

### Phase 3: CLI — Artifact Command Gap Coverage (~15%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` — Modify (add new test cases)

**Tasks:**
- [ ] `artifact get <id> <path>` → sends `GET` to `/api/v1/pipelines/{id}/artifacts/{path}`; prints text content to stdout
- [ ] `artifact stage-log <id> <nodeId>` → sends `GET` to `/api/v1/pipelines/{id}/stages/{nodeId}/log`; prints text content
- [ ] `artifact failure-report <id>` → sends `GET` to `/api/v1/pipelines/{id}/failure-report`; prints JSON
- [ ] `artifact export <id>` → sends `GET` to `/api/v1/pipelines/{id}/export`; writes bytes to derived filename; prints confirmation message

---

### Phase 4: CLI — DOT Command Gap Coverage (~10%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` — Modify (add new test cases)

**Tasks:**
- [ ] `dot render --file <path>` → sends `POST` to `/api/v1/dot/render`; writes SVG bytes to output file; prints `Saved to output.svg`
- [ ] `dot fix --file <path>` → sends `POST` to `/api/v1/dot/fix`; prints fixed dotSource
- [ ] `dot fix-stream --file <path>` → reads SSE stream from `/api/v1/dot/fix/stream`; prints deltas
- [ ] `dot iterate --file <path> --changes <text>` → sends `POST` to `/api/v1/dot/iterate`; prints new dotSource
- [ ] `dot iterate-stream --file <path> --changes <text>` → reads SSE stream; prints deltas
- [ ] `dot render` without `--file` → throws `CliException` exit code 2
- [ ] `dot fix` without `--file` → throws `CliException` exit code 2
- [ ] `dot iterate` without `--changes` → throws `CliException` exit code 2

---

### Phase 5: CLI — New Command Test Files (~15%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/ModelsCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/MainTest.kt` — Modify (add `--version` test)

**ModelsCommandTest tasks:**
- [ ] `models list` → sends `GET` to `/api/v1/models`; prints table with headers ID, PROVIDER, NAME, CONTEXT, TOOLS, VISION
- [ ] `models list` with `--output json` → prints raw JSON
- [ ] `models` with no args → defaults to `list` (same as `models list`)
- [ ] `models` unknown verb → throws `CliException` exit code 2
- [ ] `models --help` → prints help without error

**EventsCommandTest tasks:**
- [ ] `events` (no args) → calls `GET /api/v1/events`; reads SSE stream; prints each data line
- [ ] `events <id>` → calls `GET /api/v1/events/{id}`; reads SSE stream; prints each data line

**MainTest additions:**
- [ ] `--version` → prints version string containing `attractor-cli` (or version number); exits 0

---

### Phase 6: WebMonitorServer Browser-API Smoke Tests (~15%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Create

**Tasks:**

Use the same pattern as `DocsEndpointTest`: start a real `WebMonitorServer` on port 0, make HTTP requests, verify status codes.

_Route existence (GET or POST as appropriate):_
- [ ] `GET /api/pipelines` → 200
- [ ] `GET /api/pipeline-view` (missing `id` param) → 400 or 200 (read handler to determine; test documents actual behavior)
- [ ] `GET /api/pipeline-family` (missing `id` param) → 400 or 200
- [ ] `GET /api/run-artifacts` (missing `id` param) → 400 or 200
- [ ] `GET /api/stage-log` (missing params) → 400 or 404
- [ ] `GET /api/settings` → 200
- [ ] `GET /api/settings/cli-status` → 200

_Method rejection tests (non-LLM routes):_
- [ ] `DELETE /api/pipelines` → 405 (GET-only route)
- [ ] `GET /api/run` (POST-only route) → 405
- [ ] `GET /api/cancel` (POST-only route) → 405
- [ ] `GET /api/pause` (POST-only route) → 405
- [ ] `GET /api/resume` (POST-only route) → 405
- [ ] `GET /api/archive` (POST-only route) → 405
- [ ] `GET /api/unarchive` (POST-only route) → 405
- [ ] `GET /api/delete` (POST-only route) → 405

_Regression guard:_
- [ ] `GET /` → 200 (SPA still works)
- [ ] `GET /api/v1/pipelines` → 200 (REST API still works)
- [ ] `GET /docs` → 200 (docs still works)

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | Add 13 new test cases: create success, rerun, stages, artifacts.zip, stage-log, export, import, dot-upload, download-dot, DOT parameter validation, spec routes |
| `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` | Modify | Add 8 new test cases: delete, rerun, resume, cancel, archive, unarchive, iterate, iterate (no file) |
| `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` | Modify | Add 4 new test cases: get, stage-log, failure-report, export |
| `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` | Modify | Add 8 new test cases: render, fix, fix-stream, iterate, iterate-stream, and 3 missing-arg error cases |
| `src/test/kotlin/attractor/cli/commands/ModelsCommandTest.kt` | Create | New test file: models list, --output json, no args, unknown verb, --help |
| `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` | Create | New test file: events (all), events (by id) |
| `src/test/kotlin/attractor/cli/MainTest.kt` | Modify | Add `--version` test case |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Create | New test file: browser-API route existence, method rejection, regression guard |

## Definition of Done

### REST API Coverage
- [ ] `POST /api/v1/pipelines` (success) tested
- [ ] `POST /api/v1/pipelines/{id}/rerun` tested
- [ ] `GET /api/v1/pipelines/{id}/stages` tested
- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` tested
- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` tested
- [ ] `GET /api/v1/pipelines/{id}/export` tested
- [ ] `POST /api/v1/pipelines/import` tested
- [ ] `GET /api/v1/pipelines/{id}/dot` tested
- [ ] `POST /api/v1/dot/generate` parameter validation tested (400)
- [ ] `POST /api/v1/dot/render` parameter validation tested (400)
- [ ] `POST /api/v1/dot/fix` parameter validation tested (400)
- [ ] `POST /api/v1/dot/iterate` parameter validation tested (400)
- [ ] `GET /api/v1/openapi.json` tested
- [ ] `GET /api/v1/openapi.yaml` tested
- [ ] `GET /api/v1/swagger.json` tested

### CLI Coverage
- [ ] `pipeline delete` tested
- [ ] `pipeline rerun` tested
- [ ] `pipeline resume` tested
- [ ] `pipeline cancel` tested
- [ ] `pipeline archive` tested
- [ ] `pipeline unarchive` tested
- [ ] `pipeline iterate` (with and without `--file`) tested
- [ ] `artifact get` tested
- [ ] `artifact stage-log` tested
- [ ] `artifact failure-report` tested
- [ ] `artifact export` tested
- [ ] `dot render` tested
- [ ] `dot fix` tested
- [ ] `dot fix-stream` tested
- [ ] `dot iterate` tested
- [ ] `dot iterate-stream` tested
- [ ] `models list` tested
- [ ] `events` (no id) tested
- [ ] `events <id>` tested
- [ ] `--version` flag tested

### Web App Coverage
- [ ] `GET /api/pipelines` tested
- [ ] `GET /api/settings` tested
- [ ] Method rejection (405) for POST-only routes tested
- [ ] Regression: `/`, `/api/v1/pipelines`, `/docs` all return 200

### Quality
- [ ] All existing tests continue to pass (zero regressions)
- [ ] No new Gradle dependencies
- [ ] No compiler warnings
- [ ] All tests hermetic (port 0, temp dirs, teardown in `afterSpec`)
- [ ] Build passes: `export JAVA_HOME=... && gradle -p . jar`

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `POST /api/v1/pipelines` (success) may start a PipelineRunner goroutine | Low | Medium | The test `RestApiRouter` is constructed with a no-op `onUpdate` lambda; `PipelineRunner.submit` is not called until `onUpdate` triggers it — so registration succeeds without actual pipeline execution |
| `GET /api/v1/pipelines/{id}/artifacts.zip` handler behavior without logsRoot unclear | Low | Low | Read handler; test documents actual behavior (200 with empty ZIP, or 404); either is acceptable |
| Browser API routes may require `id` param formats not easy to satisfy | Low | Low | Test missing-param cases (400) and method-rejection (405); these don't require a live pipeline |
| `EventsCommand` streams indefinitely until connection closes | Medium | Low | Test fake server writes SSE data then closes connection; `getStream()` returns `Sequence` that terminates on stream close |
| `--version` may read from JAR manifest which is absent in test classpath | Medium | Low | If manifest attribute is absent, CLI should print empty version gracefully; test asserts function exits 0 and produces some output |

## Security Considerations

- All tests use ephemeral local ports (port 0) — no network exposure.
- Test files created in temp directories via `Files.createTempDirectory()` and cleaned up in `afterSpec`.
- No credentials or sensitive data in test bodies.

## Dependencies

- Sprint 014 (completed) — current codebase state
- No external dependencies

## Open Questions

1. Does `GET /api/v1/pipelines/{id}/artifacts.zip` return 200 with an empty ZIP when `logsRoot` is blank, or does it return 404? (Read handler to confirm before writing test.)
2. Does `--version` read from the JAR manifest (`Implementation-Version`)? If the attribute is absent in tests, should the test assert a graceful empty output?
3. Should `WebMonitorServerBrowserApiTest` test the full `/api/pipeline-view`, `/api/run-artifacts`, etc. with valid pipeline IDs (requiring registry setup), or just test the "missing param" / "method rejection" boundary?
