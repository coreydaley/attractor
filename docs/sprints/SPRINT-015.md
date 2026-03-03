# Sprint 015: Comprehensive Test Coverage

## Overview

Fourteen sprints of feature development have built a mature three-surface application: the browser SPA served by `WebMonitorServer`, a 35-endpoint REST API under `RestApiRouter`, and a full-featured Kotlin CLI client. Coverage has grown uneven as features outpaced tests. A gap audit reveals: roughly half the REST API endpoints are untested, a dozen CLI command verbs have no test, two entire CLI command classes (`ModelsCommand`, `EventsCommand`) have no test files, and the legacy browser API routes have only markup-presence smoke tests.

This sprint closes those gaps methodically. Every new test follows the hermetic patterns already established in the codebase — ephemeral port-0 JDK `HttpServer` fake servers for CLI tests, and a real `WebMonitorServer` or `RestApiRouter` on port 0 for integration tests. No new Gradle dependencies. The target is "adequate coverage": every non-LLM route has at least one passing test for its documented error contract or happy path, and every CLI verb has at least one test verifying the correct HTTP method and path are dispatched.

LLM-dependent endpoints (`POST /dot/generate`, `POST /dot/fix`, `POST /dot/iterate`) are tested using a mock LLM: set `execution_mode=cli`, `provider_anthropic_enabled=true`, and `cli_anthropic_command=echo` in the test store. The `echo` command returns the prompt text as the "model response," exercising the full request/response pipeline without a real model. A dedicated `RestApiLlmTest.kt` isolates this setup to avoid polluting the non-LLM test store.

## Use Cases

1. **CI regression guard**: A developer refactors `RestApiRouter`. The full test suite catches breakage in less-trafficked endpoints like `/pipelines/{id}/dot`, spec routes, and binary endpoints.
2. **CLI command confidence**: A contributor changes the HTTP path for `pipeline archive`. The new `pipeline archive` test immediately catches the regression.
3. **Models and Events coverage**: Someone extends `ModelsCommand`. The new `ModelsCommandTest` provides a stable base for assertions without starting from scratch.
4. **Browser API safety**: A `WebMonitorServer` refactor accidentally breaks `/api/run-artifacts`. `WebMonitorServerBrowserApiTest` catches the 500 before it reaches users.
5. **Spec endpoint verification**: A consumer integrates against `GET /api/v1/openapi.json`. The spec-route tests confirm the endpoint returns 200 with `application/json` content.

## Architecture

```
Test Coverage Map (Sprint 015 additions)

┌─────────────────────────────────────────────────────────────────┐
│  WebMonitorServer (port 0 real server)                          │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ WebMonitorServerBrowserApiTest (NEW)                     │   │
│  │  · GET /api/pipelines → 200                             │   │
│  │  · GET /api/run-artifacts?id= → 200 (happy path)        │   │
│  │  · GET /api/stage-log → 400 (missing params)            │   │
│  │  · GET /api/settings, /api/settings/cli-status → 200    │   │
│  │  · POST-only routes: GET → 405                          │   │
│  │  · Regression: /, /docs, /api/v1/pipelines → 200        │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  RestApiRouter (via WebMonitorServer port 0)                     │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ RestApiRouterTest.kt (EXPAND)                            │   │
│  │  + create pipeline success (201)                        │   │
│  │  + rerun (409 invalid state), stages, dot               │   │
│  │  + artifacts.zip (200 + ZIP), stage-log (404)           │   │
│  │  + export (200 + ZIP), import                           │   │
│  │  + dot upload (POST /pipelines/dot)                     │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ RestApiSpecRoutesTest.kt (NEW)                           │   │
│  │  · GET /api/v1/openapi.json → 200 application/json      │   │
│  │  · GET /api/v1/openapi.yaml → 200 application/yaml      │   │
│  │  · GET /api/v1/swagger.json → 200 application/json      │   │
│  │  · GET /api/v1/docs → 200 text/html + SwaggerUIBundle   │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ RestApiLlmTest.kt (NEW)                                  │   │
│  │  · mock LLM: execution_mode=cli, cmd=echo               │   │
│  │  · POST /dot/generate → 200 {"dotSource":"..."}         │   │
│  │  · POST /dot/fix → 200 {"dotSource":"..."}              │   │
│  │  · POST /dot/iterate → 200 {"dotSource":"..."}          │   │
│  │  · GET /dot/generate/stream → 200 SSE                   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  CLI (fake JDK HttpServer)                                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ PipelineCommandsTest.kt (EXPAND)                         │   │
│  │  + delete, rerun, resume, cancel, archive, unarchive    │   │
│  │  + iterate (with --file), iterate (no --file → exit 2)  │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ ArtifactCommandsTest.kt (EXPAND)                         │   │
│  │  + get, stage-log, failure-report, export               │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ DotCommandsTest.kt (EXPAND)                              │   │
│  │  + render, fix, fix-stream, iterate, iterate-stream     │   │
│  │  + render (no --file → exit 2), iterate (no --changes)  │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ ModelsCommandTest.kt (NEW)                               │   │
│  │  · list → table with ID/PROVIDER/NAME/CONTEXT/TOOLS     │   │
│  │  · list --output json → raw JSON                        │   │
│  │  · no args → defaults to list                           │   │
│  │  · unknown verb → exit 2                                │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ EventsCommandTest.kt (NEW)                               │   │
│  │  · events → GET /api/v1/events, prints SSE lines        │   │
│  │  · events <id> → GET /api/v1/events/{id}                │   │
│  └──────────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ MainTest.kt (EXPAND) + --version graceful output         │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### Mock LLM Strategy

LLM-dependent REST endpoints are tested by configuring the test store before the server starts:

```kotlin
store.setSetting("execution_mode", "cli")
store.setSetting("provider_anthropic_enabled", "true")
store.setSetting("cli_anthropic_command", "echo")
```

`DotGenerator.config()` reads from the store on each call. With `echo` as the CLI command, the `AnthropicCliAdapter` runs `/bin/echo <prompt_text>` and streams the prompt text back as the "model response." `extractDotSource()` strips any accidental fences and returns the text. The REST handler then wraps it in `{"dotSource":"..."}` and returns 200. No real LLM is called; the test verifies end-to-end HTTP request parsing and response formatting.

## Implementation Plan

### Phase 1: REST API — Pipeline and Artifact Gap Coverage (~25%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify

**Tasks:**

_Pipeline additions:_
- [ ] `POST /api/v1/pipelines` with valid dotSource → 201 with `id` and `status:"running"` (note: `PipelineRunner.submit()` IS called; test only asserts HTTP contract, background execution is acceptable)
- [ ] `GET /api/v1/pipelines/{id}/stages` → 200 with `stages` array (register a pipeline first)
- [ ] `POST /api/v1/pipelines/{id}/rerun` (registered idle pipeline) → 409 `INVALID_STATE` (pipeline is not running/paused, so rerun is invalid)
- [ ] `GET /api/v1/pipelines/{id}/dot` → 200 with `text/plain` content-type containing the pipeline's dotSource
- [ ] `POST /api/v1/pipelines/dot` with valid DOT body → 201 (upload DOT, auto-parsed; same handler as create)

_Artifact additions:_
- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` with blank logsRoot → 200 with ZIP payload (handler builds empty ZIP when no files present)
- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` with no log file → 404 `NOT_FOUND`
- [ ] `GET /api/v1/pipelines/{id}/export` → 200 with `application/zip` content-type
- [ ] `POST /api/v1/pipelines/import` with empty body → 400 `BAD_REQUEST`

---

### Phase 2: REST API — Spec Routes (~10%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiSpecRoutesTest.kt` — Create

**Tasks:**
- [ ] `GET /api/v1/openapi.json` → 200 with `application/json` content-type
- [ ] `GET /api/v1/openapi.yaml` → 200 with `application/yaml` or `text/yaml` content-type
- [ ] `GET /api/v1/swagger.json` → 200 with `application/json` content-type (alias for openapi.json)
- [ ] `GET /api/v1/docs` → 200 with `text/html` content-type and `SwaggerUIBundle` in body

---

### Phase 3: REST API — LLM Endpoints with Mock LLM (~10%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiLlmTest.kt` — Create

**Setup:**
```kotlin
beforeSpec {
    store.setSetting("execution_mode", "cli")
    store.setSetting("provider_anthropic_enabled", "true")
    store.setSetting("cli_anthropic_command", "echo")
}
```

**Tasks:**

_Parameter validation (400 cases, no LLM needed):_
- [ ] `POST /api/v1/dot/generate` missing `prompt` → 400 `BAD_REQUEST`
- [ ] `POST /api/v1/dot/render` missing `dotSource` → 400 `BAD_REQUEST`
- [ ] `POST /api/v1/dot/fix` missing `dotSource` → 400 `BAD_REQUEST`
- [ ] `POST /api/v1/dot/iterate` missing `baseDot` → 400 `BAD_REQUEST`

_Happy path with mock LLM (`echo` command):_
- [ ] `POST /api/v1/dot/generate` with `{"prompt":"test"}` → 200 with `dotSource` field present
- [ ] `POST /api/v1/dot/fix` with `{"dotSource":"digraph G { a -> b }"}` → 200 with `dotSource` field present
- [ ] `POST /api/v1/dot/iterate` with `{"baseDot":"digraph G { a -> b }","changes":"add node c"}` → 200 with `dotSource` field present
- [ ] `GET /api/v1/dot/generate/stream?prompt=test` → 200 with `text/event-stream` content-type

---

### Phase 4: CLI — Pipeline Command Gap Coverage (~15%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` — Modify

**Tasks:**
- [ ] `pipeline delete <id>` → sends `DELETE` to `/api/v1/pipelines/{id}`; prints confirmation
- [ ] `pipeline rerun <id>` → sends `POST` to `/api/v1/pipelines/{id}/rerun`; prints result
- [ ] `pipeline resume <id>` → sends `POST` to `/api/v1/pipelines/{id}/resume`; prints result
- [ ] `pipeline cancel <id>` → sends `POST` to `/api/v1/pipelines/{id}/cancel`; prints `cancelled: true`
- [ ] `pipeline archive <id>` → sends `POST` to `/api/v1/pipelines/{id}/archive`; prints `archived: true`
- [ ] `pipeline unarchive <id>` → sends `POST` to `/api/v1/pipelines/{id}/unarchive`; prints `unarchived: true`
- [ ] `pipeline iterate <id> --file <path>` → sends `POST` to `/api/v1/pipelines/{id}/iterations`; body contains dotSource from file
- [ ] `pipeline iterate <id>` without `--file` → throws `CliException` exit code 2

---

### Phase 5: CLI — Artifact Command Gap Coverage (~10%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` — Modify

**Tasks:**
- [ ] `artifact get <id> <path>` → sends `GET` to `/api/v1/pipelines/{id}/artifacts/{path}`; prints text content
- [ ] `artifact stage-log <id> <nodeId>` → sends `GET` to `/api/v1/pipelines/{id}/stages/{nodeId}/log`; prints text content
- [ ] `artifact failure-report <id>` → sends `GET` to `/api/v1/pipelines/{id}/failure-report`; prints JSON or table
- [ ] `artifact export <id>` → sends `GET` to `/api/v1/pipelines/{id}/export`; writes ZIP bytes to file; prints confirmation

---

### Phase 6: CLI — DOT Command Gap Coverage (~10%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` — Modify

**Tasks:**
- [ ] `dot render --file <path>` → sends `POST` to `/api/v1/dot/render`; fake server returns `{"svg":"<svg>...</svg>"}`; asserts output file contains SVG text; prints `Saved to output.svg`
- [ ] `dot render` without `--file` → throws `CliException` exit code 2
- [ ] `dot fix --file <path>` → sends `POST` to `/api/v1/dot/fix`; prints `dotSource` from response
- [ ] `dot fix-stream --file <path>` → reads SSE stream from `/api/v1/dot/fix/stream`; prints deltas
- [ ] `dot iterate --file <path> --changes <text>` → sends `POST` to `/api/v1/dot/iterate`; prints `dotSource`
- [ ] `dot iterate-stream --file <path> --changes <text>` → reads SSE stream from `/api/v1/dot/iterate/stream`; prints deltas
- [ ] `dot iterate` without `--changes` → throws `CliException` exit code 2

---

### Phase 7: CLI — New Test Files and Main Additions (~10%)

**Files:**
- `src/test/kotlin/attractor/cli/commands/ModelsCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/MainTest.kt` — Modify

**ModelsCommandTest tasks:**
- [ ] `models list` → sends `GET` to `/api/v1/models`; prints table with headers ID, PROVIDER, NAME, CONTEXT, TOOLS, VISION
- [ ] `models list --output json` → prints raw JSON response
- [ ] `models` (no args) → defaults to `list` behavior
- [ ] `models <unknown>` → throws `CliException` exit code 2
- [ ] `models --help` → prints help text without error

**EventsCommandTest tasks:**
- [ ] `events` (no args) → sends `GET` to `/api/v1/events`; reads SSE stream; prints each `data:` line
- [ ] `events <id>` → sends `GET` to `/api/v1/events/{id}`; reads SSE stream; prints lines

**MainTest additions:**
- [ ] `--version` → exits 0 and produces some output (version string may be `unknown` in test context; asserts non-crash)

---

### Phase 8: Web Browser API Tests (~8%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Create

**Setup:** Start a real `WebMonitorServer` on port 0. Pre-register one pipeline in the registry for happy-path route tests.

**Tasks:**

_Read-only routes (GET):_
- [ ] `GET /api/pipelines` → 200 with JSON array
- [ ] `GET /api/pipeline-view?id=<known-id>` → 200 (registered pipeline)
- [ ] `GET /api/pipeline-view` (missing id) → 400
- [ ] `GET /api/pipeline-family?id=<known-id>` → 200
- [ ] `GET /api/run-artifacts?id=<known-id>` → 200 (may be empty list)
- [ ] `GET /api/stage-log` (missing params) → 400
- [ ] `GET /api/settings` → 200
- [ ] `GET /api/settings/cli-status` → 200

_Method rejection (only for routes with explicit guards):_
- [ ] `GET /api/pipeline-view` correct method check shows handler guards non-GET → 405 (has explicit guard)
- [ ] `GET /api/run` (POST-only, has guard) → 405
- [ ] `GET /api/cancel` (POST-only, has guard) → 405
- [ ] `GET /api/pause` (POST-only, has guard) → 405
- [ ] `GET /api/resume` (POST-only, has guard) → 405
- [ ] `GET /api/archive` (POST-only, has guard) → 405
- [ ] `GET /api/unarchive` (POST-only, has guard) → 405
- [ ] `GET /api/delete` (POST-only, has guard) → 405

_Regression guard:_
- [ ] `GET /` → 200
- [ ] `GET /api/v1/pipelines` → 200
- [ ] `GET /docs` → 200

---

### Phase 9: CI Verification (~2%)

**Tasks:**
- [ ] Run full test suite: `export JAVA_HOME=... && gradle -p . test`
- [ ] Confirm zero regressions in existing tests
- [ ] Produce endpoint/verb checklist in PR notes confirming each previously-missing item is now covered
- [ ] If any SSE/binary tests are flaky, harden with deterministic fake-server responses and explicit connection-close semantics

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | Add create pipeline success, rerun, stages, dot, artifacts.zip, stage-log, export, import, dot-upload |
| `src/test/kotlin/attractor/web/RestApiSpecRoutesTest.kt` | Create | OpenAPI JSON, YAML, swagger alias, and Swagger UI docs route contract tests |
| `src/test/kotlin/attractor/web/RestApiLlmTest.kt` | Create | LLM endpoint tests: 400 parameter validation + 200 happy path via `echo` mock |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Create | Legacy browser /api/* route smoke, happy-path, and method-guard tests |
| `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` | Modify | Add delete, rerun, resume, cancel, archive, unarchive, iterate verbs |
| `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` | Modify | Add get, stage-log, failure-report, export verbs |
| `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` | Modify | Add render, fix, fix-stream, iterate, iterate-stream, and missing-arg error cases |
| `src/test/kotlin/attractor/cli/commands/ModelsCommandTest.kt` | Create | Models command: list, --output json, default, unknown verb, --help |
| `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` | Create | Events command: stream all, stream by id |
| `src/test/kotlin/attractor/cli/MainTest.kt` | Modify | Add `--version` graceful output test |

## Definition of Done

### REST API
- [ ] `POST /api/v1/pipelines` (success → 201) tested
- [ ] `POST /api/v1/pipelines/{id}/rerun` tested (409)
- [ ] `GET /api/v1/pipelines/{id}/stages` tested
- [ ] `GET /api/v1/pipelines/{id}/dot` tested
- [ ] `GET /api/v1/pipelines/{id}/artifacts.zip` tested
- [ ] `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` (404) tested
- [ ] `GET /api/v1/pipelines/{id}/export` tested
- [ ] `POST /api/v1/pipelines/import` (400) tested
- [ ] `POST /api/v1/pipelines/dot` tested
- [ ] `POST /api/v1/dot/generate` — 400 (missing param) AND 200 (mock LLM) tested
- [ ] `POST /api/v1/dot/render` — 400 (missing param) tested
- [ ] `POST /api/v1/dot/fix` — 400 (missing param) AND 200 (mock LLM) tested
- [ ] `POST /api/v1/dot/iterate` — 400 (missing param) AND 200 (mock LLM) tested
- [ ] `GET /api/v1/dot/generate/stream` — 200 SSE tested
- [ ] `GET /api/v1/openapi.json` tested
- [ ] `GET /api/v1/openapi.yaml` tested
- [ ] `GET /api/v1/swagger.json` tested
- [ ] `GET /api/v1/docs` tested

### CLI
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
- [ ] `dot render` (success + missing --file) tested
- [ ] `dot fix` tested
- [ ] `dot fix-stream` tested
- [ ] `dot iterate` tested
- [ ] `dot iterate-stream` tested
- [ ] `models list` tested
- [ ] `models --output json` tested
- [ ] `events` (all) tested
- [ ] `events <id>` tested
- [ ] `--version` tested

### Web App
- [ ] `GET /api/pipelines` tested
- [ ] `GET /api/pipeline-view` (happy path + missing id) tested
- [ ] `GET /api/run-artifacts` (happy path) tested
- [ ] `GET /api/settings` and `/api/settings/cli-status` tested
- [ ] Method rejection (405) tested for routes with explicit guards
- [ ] Regression: `/`, `/api/v1/pipelines`, `/docs` all return 200

### Quality
- [ ] All existing tests continue to pass (zero regressions)
- [ ] No new Gradle dependencies
- [ ] No compiler warnings
- [ ] All tests hermetic (port 0, temp dirs, teardown in `afterSpec`)
- [ ] Build passes: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `POST /api/v1/pipelines` starts background pipeline thread that interferes with teardown | Medium | Low | Pipeline runs asynchronously; test asserts only HTTP contract (201 + id + status); background thread exits naturally when store is closed in `afterSpec` |
| `echo` mock LLM is too slow for SSE stream test (process startup overhead) | Low | Low | `getStream()` reads until EOF; `echo` exits immediately; stream test completes fast |
| `GET /api/v1/artifacts.zip` for pipeline with no logsRoot may not return `PK` ZIP magic | Low | Low | Read handler to confirm: empty ZIP file is returned with PK header even when no artifacts exist |
| SSE test hangs if fake server doesn't close response body | Medium | Medium | Fake server writes data then explicitly closes response body via `ex.responseBody.close()` |
| `--version` produces empty output in test context (no JAR manifest) | Medium | Low | Test asserts exits 0 and produces any output (even empty string is acceptable) |

## Security Considerations

- All tests use ephemeral local ports (port 0) — no external network exposure.
- Test fixtures use `Files.createTempDirectory()` and `Files.createTempFile()` with cleanup in `afterSpec`.
- No credentials or API keys needed for any test in this sprint.
- `POST /api/v1/pipelines/import` boundary test uses a malformed/empty ZIP — confirms the handler rejects invalid input gracefully.

## Dependencies

- Sprint 014 (completed) — current codebase as test target
- No external dependencies

## Open Questions

1. Does `GET /api/v1/pipelines/{id}/artifacts.zip` for a pipeline with blank `logsRoot` return 200 with empty ZIP, or 404? (Confirmed by reading `handleDownloadArtifactsZip` before writing test.)
2. Should the `RestApiLlmTest` stream test (`GET /dot/generate/stream?prompt=test`) verify specific SSE data lines, or just assert `200 + text/event-stream` content-type?
3. For `EventsCommandTest`, should we verify that printed lines match specific SSE `data:` line format, or just confirm the command doesn't throw?
