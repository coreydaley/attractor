# Sprint 012: Kotlin CLI Client for REST API v1

## Overview

Attractor now exposes a complete REST API v1 under `/api/v1` (35 endpoints), but the primary user
experience is still the web UI and low-level `curl` calls. That gap blocks practical terminal-based
workflows for users who want scriptable and discoverable interaction without hand-crafting HTTP
requests.

This sprint introduces a first-party Kotlin CLI client that wraps the REST API v1 with stable,
well-documented commands, readable default output, machine-readable JSON output, and predictable
error handling. The CLI is shipped as a separate runnable artifact from the server JAR and is
integrated into existing Make and GitHub Actions workflows so it is built/tested in CI and attached
to tag releases.

Implementation stays within current repo constraints: no new Gradle dependencies, manual argument
parsing, and hermetic tests (no external network). The CLI reuses existing dependencies already in
`build.gradle.kts` (`okhttp` + `kotlinx-serialization-json`) and existing endpoint contracts in
`docs/api/rest-v1.md`.

## Use Cases

1. **Daily operator workflow**: A user lists pipelines, inspects status, pauses/resumes/reruns, and
   downloads artifacts entirely from the terminal.
2. **Script automation**: Bash scripts call `attractor` commands with `--output json` and parse
   responses deterministically.
3. **Remote instance targeting**: Teams point the same CLI at different Attractor instances via
   `--host` without changing code.
4. **DOT lifecycle from terminal**: Users generate/fix/iterate/validate/render DOT and submit runs
   without opening the web dashboard.
5. **Release consumption**: Users can download a dedicated CLI JAR from GitHub Releases, separate
   from the server artifact.

## Architecture

### High-level flow

```text
CLI command (java -jar attractor-cli-<version>.jar ...)
  -> CliMain parses global flags + command path + command flags
      -> CliCommand dispatcher resolves endpoint mapping
          -> ApiClient (OkHttp) builds HTTP request to /api/v1/*
              -> Rest API v1 response
                  -> OutputFormatter (text/table/json/file/stream)
```

### Package layout

```text
src/main/kotlin/attractor/cli/
  CliMain.kt                  entrypoint + top-level parser
  CliParser.kt                argv tokenization and validation helpers
  CliCommandDispatcher.kt     command routing
  ApiClient.kt                HTTP request helpers (JSON + stream + binary)
  OutputFormatter.kt          human output + --output json handling
  Commands.kt                 command implementations grouped by domain

src/test/kotlin/attractor/cli/
  CliParserTest.kt
  CliCommandTest.kt
  ApiClientTest.kt
  OutputFormatterTest.kt
```

### Command surface (maps 1:1 to REST v1 endpoint families)

```text
Global:
  --host <url>         default http://localhost:8080
  --output <text|json> default text
  --help

pipeline:
  list
  get <id>
  create --dot-file <path> [--file-name <name>] [--simulate] [--auto-approve=<bool>] [--original-prompt <text>]
  update <id> [--dot-file <path>] [--original-prompt <text>]
  delete <id>
  rerun <id>
  pause <id>
  resume <id>
  cancel <id>
  archive <id>
  unarchive <id>
  iterate <id> --dot-file <path> [--file-name <name>] [--original-prompt <text>]
  family <id>
  stages <id>
  watch <id> [--interval-ms <n>] [--timeout-ms <n>]      # convenience wrapper over GET /pipelines/{id}

artifact:
  list <id>
  get <id> --path <artifact-path>
  download-zip <id> --out <file>
  stage-log <id> --node <nodeId>
  failure-report <id>
  export <id> --out <file>
  import --zip <file> [--on-conflict <skip|overwrite>]

dot:
  validate --dot-file <path>
  render --dot-file <path> [--out <svg-file>]
  generate --prompt <text>
  generate-stream --prompt <text>
  fix --dot-file <path> [--error <text>]
  fix-stream --dot-file <path> [--error <text>]
  iterate --dot-file <path> --instruction <text>
  iterate-stream --dot-file <path> --instruction <text>

settings:
  list
  get <key>
  set <key> --value <value>

models:
  list

events:
  stream [--id <pipelineId>]
```

## Implementation Plan

### Phase 1: CLI scaffolding and command model (~15%)

**Files:**
- `src/main/kotlin/attractor/cli/CliMain.kt` — Create
- `src/main/kotlin/attractor/cli/CliParser.kt` — Create
- `src/main/kotlin/attractor/cli/CliCommandDispatcher.kt` — Create

**Tasks:**
- [ ] Create dedicated CLI entrypoint `attractor.cli.CliMainKt` with `main(args)`.
- [ ] Implement manual parser for:
  - global flags (`--host`, `--output`, `--help`)
  - command path (`pipeline`, `artifact`, `dot`, `settings`, `models`, `events`)
  - command-local flags/positionals.
- [ ] Implement consistent usage/help renderer:
  - top-level help
  - per-command help (`attractor pipeline --help`, etc.)
  - non-zero exit on invalid args.
- [ ] Define exit code contract:
  - `0` success
  - `1` API/connection/runtime error
  - `2` usage/validation error.

### Phase 2: HTTP client and response handling (~20%)

**Files:**
- `src/main/kotlin/attractor/cli/ApiClient.kt` — Create
- `src/main/kotlin/attractor/cli/OutputFormatter.kt` — Create

**Tasks:**
- [ ] Build `ApiClient` with existing OkHttp dependency:
  - JSON request/response helpers
  - plain text response helper (logs)
  - binary download helper (ZIP/export/svg to file)
  - SSE stream reader helper (line-by-line event output).
- [ ] Implement shared error translation:
  - network failure -> `cannot connect to <host>`
  - non-2xx JSON error envelope -> show `error` + `code`
  - malformed response -> safe fallback message.
- [ ] Implement output policy:
  - `--output text`: readable summaries/tables
  - `--output json`: raw JSON passthrough (or normalized JSON object for non-JSON endpoints)
  - stream commands always line-stream while running.

### Phase 3: Pipeline and artifact command families (~25%)

**Files:**
- `src/main/kotlin/attractor/cli/Commands.kt` — Create
- `src/main/kotlin/attractor/cli/CliCommandDispatcher.kt` — Modify

**Tasks:**
- [ ] Implement all pipeline CRUD/lifecycle/family/stages commands mapped to `/api/v1/pipelines*`.
- [ ] Implement `pipeline watch` as polling convenience with interval/timeout options.
- [ ] Implement all artifact/import/export commands:
  - file content fetch
  - stage log
  - failure report
  - artifacts ZIP download
  - export ZIP and import ZIP.
- [ ] Enforce file write safety:
  - require `--out` for binary output
  - refuse to write directory paths
  - clear overwrite behavior (overwrite by default, document it).

### Phase 4: DOT/settings/models/events command families (~20%)

**Files:**
- `src/main/kotlin/attractor/cli/Commands.kt` — Modify
- `src/main/kotlin/attractor/cli/OutputFormatter.kt` — Modify

**Tasks:**
- [ ] Implement DOT command set (sync + stream variants).
- [ ] Implement settings commands (`list`, `get`, `set`) with key/value validation where practical.
- [ ] Implement models listing.
- [ ] Implement events streaming (`/api/v1/events`, `/api/v1/events/{id}`) with Ctrl+C-friendly behavior.
- [ ] Add compact tabular text output for:
  - `pipeline list`
  - `pipeline family`
  - `models list`
  - `artifact list`.

### Phase 5: Build system integration (Gradle + Makefile) (~10%)

**Files:**
- `build.gradle.kts` — Modify
- `Makefile` — Modify

**Tasks:**
- [ ] Add dedicated fat-JAR task for CLI artifact:
  - output name: `attractor-cli-${version}.jar`
  - `Main-Class`: `attractor.cli.CliMainKt`
  - include runtime classpath similarly to existing server `jar` task.
- [ ] Keep existing server `jar` behavior intact.
- [ ] Add Makefile targets and help entries:
  - `cli-jar` (build CLI jar)
  - `cli-run` (run CLI via Gradle for local iteration, optional)
  - `cli` alias if needed for discoverability.
- [ ] Ensure `.PHONY` and help text include new targets.

### Phase 6: CI and release automation (~5%)

**Files:**
- `.github/workflows/ci.yml` — Modify
- `.github/workflows/release.yml` — Modify

**Tasks:**
- [ ] CI: ensure CLI build path is exercised on push/PR:
  - either `./gradlew build` includes CLI task wiring, or add explicit `./gradlew cliJar` step.
- [ ] Release: attach both server and CLI jars to GitHub Release for tag pushes (`v*`).
- [ ] Ensure artifact selection is deterministic (`coreys-attractor-*.jar` + `attractor-cli-*.jar`).

### Phase 7: Tests and docs (~5%)

**Files:**
- `src/test/kotlin/attractor/cli/CliParserTest.kt` — Create
- `src/test/kotlin/attractor/cli/CliCommandTest.kt` — Create
- `src/test/kotlin/attractor/cli/ApiClientTest.kt` — Create
- `src/test/kotlin/attractor/cli/OutputFormatterTest.kt` — Create
- `README.md` — Modify
- `docs/api/rest-v1.md` — Modify (optional CLI examples section)

**Tasks:**
- [ ] Parser tests for help output, missing args, bad flags, defaults.
- [ ] Command mapping tests for endpoint path/method correctness.
- [ ] API client tests with local in-process HTTP server (`com.sun.net.httpserver.HttpServer`), no external network.
- [ ] Output tests for table formatting and JSON passthrough.
- [ ] Update README with CLI quickstart and examples.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/cli/CliMain.kt` | Create | CLI entrypoint and top-level execution flow |
| `src/main/kotlin/attractor/cli/CliParser.kt` | Create | Manual argument parser and validation |
| `src/main/kotlin/attractor/cli/CliCommandDispatcher.kt` | Create | Route parsed commands to implementations |
| `src/main/kotlin/attractor/cli/ApiClient.kt` | Create | REST v1 transport layer using OkHttp |
| `src/main/kotlin/attractor/cli/OutputFormatter.kt` | Create | Text/table/json output formatting |
| `src/main/kotlin/attractor/cli/Commands.kt` | Create | Command handlers for all endpoint families |
| `src/test/kotlin/attractor/cli/CliParserTest.kt` | Create | Parser/usage contract tests |
| `src/test/kotlin/attractor/cli/CliCommandTest.kt` | Create | Command-to-endpoint mapping tests |
| `src/test/kotlin/attractor/cli/ApiClientTest.kt` | Create | HTTP behavior/error handling tests |
| `src/test/kotlin/attractor/cli/OutputFormatterTest.kt` | Create | Human-readable and JSON output tests |
| `build.gradle.kts` | Modify | Add dedicated CLI fat-JAR task and entrypoint wiring |
| `Makefile` | Modify | Add `cli-jar` and related helper targets |
| `.github/workflows/ci.yml` | Modify | Build/test CLI in CI |
| `.github/workflows/release.yml` | Modify | Release server + CLI JAR assets |
| `README.md` | Modify | CLI usage documentation |

## Definition of Done

- [ ] `make cli-jar` produces `build/libs/attractor-cli-<version>.jar`.
- [ ] `java -jar build/libs/attractor-cli-<version>.jar --help` prints complete command help.
- [ ] CLI covers all REST v1 endpoint families (pipelines, lifecycle, artifacts, DOT, settings, models, events).
- [ ] Global `--host` works for all commands; default host is `http://localhost:8080`.
- [ ] `--output json` works for all JSON-capable commands.
- [ ] Binary outputs (`artifacts.zip`, `export`, `render --out`) write to files correctly.
- [ ] Stream commands (`events`, DOT stream commands) show incremental output until interrupted.
- [ ] CLI unit/integration tests are hermetic and pass under `make test`.
- [ ] CI builds/tests include CLI path.
- [ ] Release workflow uploads both server and CLI JAR assets on tag push.
- [ ] Make help output documents new CLI targets.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Manual parser complexity causes inconsistent behavior across commands | Medium | Medium | Centralize parser primitives and enforce per-command tests for errors/help |
| SSE handling differences across endpoints produce brittle stream UX | Medium | Medium | Use one shared stream reader abstraction and test against fixture event streams |
| Gradle fat-JAR task collision with existing `jar` output | Low | High | Use distinct task/output name and explicit archive base name |
| Release workflow accidentally uploads only one jar | Medium | Medium | Explicitly match both jar patterns and validate in workflow step output |
| CLI/table output drifts from schema changes | Medium | Low | Keep JSON passthrough mode as stable fallback; table output best-effort |

## Security Considerations

- CLI only talks to configured host over HTTP(S); no shell execution in command handlers.
- File output commands must write only to user-provided paths and report failures clearly.
- Preserve server-side validation as source of truth; CLI performs lightweight pre-validation only.
- Avoid printing sensitive headers/tokens in error paths.

## Dependencies

- Sprint 010 REST API v1 availability (`docs/api/rest-v1.md`, `RestApiRouter.kt`).
- Sprint 011 settings surface (if merged) is consumed by `settings` commands but not required for
  baseline CLI architecture.
- Existing dependencies only (`okhttp`, `kotlinx-serialization-json`) — no new Gradle dependencies.

## Open Questions

1. Should binary name target be `attractor` or `attractor-cli` for local install/docs examples?
2. Should `pipeline watch` be in Sprint 012 scope or deferred to keep strict 1:1 endpoint mapping?
3. For binary outputs, should overwrite require `--force`, or is default overwrite acceptable?
4. Should stream commands support `--timeout-ms` from day one for CI-friendly usage?
