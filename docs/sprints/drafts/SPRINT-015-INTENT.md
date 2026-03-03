# Sprint 015 Intent: Comprehensive Test Coverage for Web App, REST API, and CLI

## Seed

ensure that we have adequate test coverage for the web application, restful api, and command line application

## Context

The project has three testable application surfaces:

1. **Web Application** — `WebMonitorServer` serves the SPA (`GET /`), browser-specific API routes (e.g., `/api/run`, `/api/stage-log`), static assets, and the `/docs` docs page. Currently only markup-presence tests exist (DocsEndpointTest, CreateDotUploadTest) with minimal regression guards.

2. **REST API** — `RestApiRouter` handles 35+ endpoints under `/api/v1/`. `RestApiRouterTest` and `SettingsEndpointsTest` cover about half the surface. Significant untested areas remain, especially: create-success, rerun, stages, stage-log, artifacts.zip, export/import, dot-upload, openapi/swagger spec routes, and the response format for various status codes.

3. **CLI** — The `attractor.cli` package provides commands for all REST API endpoints. Existing tests cover: ApiClient, Formatter, Main flags, and core pipeline/artifact/dot/settings commands. Missing coverage: pipeline delete/rerun/resume/cancel/archive/unarchive/iterate; artifact get/stage-log/failure-report/export; dot render/fix/fix-stream/iterate/iterate-stream; models list; events; and the `--version` flag.

## Recent Sprint Context

- **Sprint 012** — Built the full Kotlin CLI client (`attractor-cli`), wiring all 35 REST endpoints to CLI commands, with fat JAR build infrastructure.
- **Sprint 013** — Added in-app `/docs` documentation window to WebMonitorServer with four content tabs (Web App, REST API, CLI, DOT Format). Added `DocsEndpointTest`.
- **Sprint 014** — Added DOT file upload button to the Create page. Added `CreateDotUploadTest` for markup-presence and regression checks.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Route dispatcher + 35+ handler methods
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — SPA host + browser-API routes
- `src/main/kotlin/attractor/cli/commands/` — PipelineCommands, ArtifactCommands, DotCommands, SettingsCommands, ModelsCommand, EventsCommand
- `src/main/kotlin/attractor/cli/Main.kt` — Top-level flag handling including `--version`
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Reference pattern for REST API tests
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` — Reference pattern for CLI command tests
- Test patterns: Kotest `FunSpec`, ephemeral `InetSocketAddress(0)` ports, JDK HttpServer fake servers for CLI, real `WebMonitorServer`/`RestApiRouter` on port 0 for web tests

## Constraints

- Must follow project conventions (Kotest FunSpec, no new Gradle dependencies)
- No network calls to real LLM APIs — LLM-dependent endpoints (dot/generate, dot/fix, dot/iterate, dot/render with live graphviz) must be tested at the HTTP routing layer (verify correct response code/format for missing-param cases, or using mocked context)
- No new Gradle dependencies
- All tests hermetic: temp files, temp dirs, port 0, teardown in `afterSpec`
- Test files stay in `src/test/kotlin/attractor/` hierarchy

## Success Criteria

1. **REST API:** All non-LLM-dependent routes have at least one passing test (happy path or documented error). LLM routes have tests for at least their parameter validation / 400 error cases.
2. **CLI:** Every CLI command verb has at least one test verifying the correct HTTP method, path, and basic output.
3. **Web App:** WebMonitorServer browser-API routes (`/api/run`, `/api/stage-log`, etc.) have basic smoke tests; the openapi/swagger spec routes return expected status codes.
4. **--version flag** is tested.
5. No regressions: all existing tests continue to pass.

## Verification Strategy

- Correctness reference: existing `RestApiRouterTest` and `PipelineCommandsTest` patterns
- Spec: `docs/api/rest-v1.md` defines expected request/response shapes
- Testing approach: add new test cases to existing test files where a natural fit exists; create new test files (`ModelsCommandTest`, `EventsCommandTest`, `RestApiCoverageTest`, `WebMonitorServerBrowserApiTest`) for areas without test files
- Edge cases: path traversal (already tested), unknown IDs (already tested), binary responses (test with ZIP magic bytes)

## Uncertainty Assessment

- Correctness uncertainty: **Low** — existing patterns are clear; gaps are mechanical
- Scope uncertainty: **Medium** — LLM-dependent endpoints need careful boundary (test routing, not LLM)
- Architecture uncertainty: **Low** — extends existing test architecture, no new patterns needed

## Open Questions

1. Should we add `ModelsCommandTest` and `EventsCommandTest` as new files, or add cases to existing files?
2. For the WebMonitorServer browser-API routes, should we test every route or just smoke-test that they respond (not 404)?
3. Should dot/render be tested with a mock graphviz (or just test the 400/missing-param case)?
4. Should we add tests for the OpenAPI spec routes (`/api/v1/openapi.json`, `/api/v1/openapi.yaml`, `/api/v1/swagger.json`)?
