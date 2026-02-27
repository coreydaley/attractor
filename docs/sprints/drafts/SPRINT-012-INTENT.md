# Sprint 012 Intent: Kotlin CLI Client for Attractor REST API

## Seed

> users should be able to interact with this application via a command that can be run in the cli. Create a command line application, written in Kotlin that can interact with this application, it should use the openapi spec that we created for api/v1 and should include useful help through the command and documentation, it should also have it's own makefile target to build it, along with github actions to build and test it. When creating a tag in this repository and pushing it, the cli application should also be build and released as part of the release process on github.

## Context

The application now has a full REST API v1 (Sprint 010) with 35 documented endpoints covering pipeline management, lifecycle control, DOT operations, settings, models, and SSE events. The API spec lives at `docs/api/rest-v1.md`. The server is a JVM application built with Kotlin + Gradle; it already depends on OkHttp 4.12.0 and kotlinx-serialization-json 1.6.3 — both of which can be reused in a CLI tool at no additional dependency cost.

There is currently no CLI tool: the only way to interact with Attractor programmatically is via direct HTTP (e.g., `curl`) or the browser UI. This sprint delivers a purpose-built Kotlin CLI that wraps the REST API and provides a polished command-line interface.

## Recent Sprint Context

- **Sprint 009** — Critical Path Test Coverage: established Kotest FunSpec patterns, temp dirs, hermetic tests, no network calls in unit tests.
- **Sprint 010** — Full REST API v1: `RestApiRouter.kt`, 35 endpoints, `docs/api/rest-v1.md` spec, existing CI/release GitHub Actions workflows.
- **Sprint 011** — AI Provider Execution Mode (in_progress): adds settings for execution mode and per-provider toggles; extends `/api/v1/settings` surface that the CLI will also expose.

## Relevant Codebase Areas

### API to wrap
- `docs/api/rest-v1.md` — canonical source-of-truth for all 35 endpoints
- All `/api/v1/` routes implemented in `src/main/kotlin/attractor/web/RestApiRouter.kt`

### Build and release infrastructure
- `build.gradle.kts` — single-project Gradle build; app plugin with `mainClass = attractor.MainKt`
- `Makefile` — build targets: `build`, `test`, `jar`, `run`, `clean`, `openapi`; pattern to follow
- `.github/workflows/ci.yml` — build + test on push/PR to main; uploads test results artifact
- `.github/workflows/release.yml` — on `v*` tag push: build fat JAR + create GitHub Release

### Existing dependencies (reusable without adding new deps)
- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP client for CLI → server calls
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3` — JSON response parsing/display
- JDK 21 built-in: no need for picocli or Commons CLI (argument parsing is straightforward)

### New code locations
- `src/main/kotlin/attractor/cli/` — CLI source code
- `src/test/kotlin/attractor/cli/` — CLI tests

## Constraints

- **No new Gradle dependencies** — OkHttp (HTTP) and kotlinx-serialization-json (parsing) are already present
- Must be built as a **separate fat JAR** (different `Main-Class` from the server)
- Must follow existing Makefile pattern; new targets added to `.PHONY` and `help` output
- CLI tests must be hermetic (no network calls; mock/fake HTTP server or use test doubles)
- Must integrate with existing CI and release workflows (extend, don't replace)
- Argument parsing via manual Kotlin implementation (no third-party CLI frameworks)

## Success Criteria

1. `attractor-cli-<version>.jar` is buildable via `make cli-jar`
2. `java -jar attractor-cli-*.jar --help` prints well-formatted help with all commands
3. All 35 REST API v1 endpoints are accessible via CLI subcommands
4. Output is human-readable by default (table/text) with `--output json` for machine-readable JSON
5. Global `--host` flag allows targeting any Attractor instance
6. CI workflow builds and tests the CLI on every push/PR
7. Release workflow uploads the CLI JAR as a GitHub Release asset alongside the server JAR
8. `make cli-jar` adds a `cli` target documented in `make help`

## Verification Strategy

- **Unit tests**: Fake HTTP responses via `MockWebServer` (OkHttp's bundled test server, already in the classpath path via OkHttp test artifacts, OR a simple manual fake using JDK's `com.sun.net.httpserver.HttpServer`) to verify each command sends the correct request and formats the response correctly.
- **Argument parsing tests**: Verify `--help` output, correct flag parsing, error messages for missing args.
- **Build verification**: `make cli-jar` produces a runnable JAR with correct `Main-Class`.
- **Integration reference**: `docs/api/rest-v1.md` defines correct behavior for each endpoint.
- **Edge cases identified**:
  - `--host` default (`http://localhost:8080`) vs override
  - Server unreachable → clear "cannot connect" error message, exit code 1
  - API error response → print `error` field, exit code 1
  - SSE stream commands (`events`, `dot generate/stream`) → line-by-line output
  - Binary download commands (`artifacts.zip`, `export`) → write to file, not stdout

## Uncertainty Assessment

- **Correctness uncertainty**: Low — 35 well-documented endpoints; CLI wraps them 1:1
- **Scope uncertainty**: Low — seed is specific; use the OpenAPI spec as definition of done
- **Architecture uncertainty**: Low — same Gradle project, new source set, second fat JAR task

## Open Questions

1. **Command name**: Should the installed binary be `attractor`, `attractor-cli`, or `actr`? (Proposed: `attractor`)
2. **Table output**: What columns to show for `pipeline list`? (Proposed: ID, Name, Status, Started)
3. **SSE follow commands**: Should `events` and `dot generate/stream` follow until Ctrl+C, or should there be a `--timeout` flag?
4. **Watch mode**: Should there be a `pipeline watch <id>` command that tails live status until completion?
5. **Output file flag**: Should `artifacts.zip` and `export` default to a local file, or require `--output <path>`?
