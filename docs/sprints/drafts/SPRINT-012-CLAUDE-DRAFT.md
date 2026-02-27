# Sprint 012: Kotlin CLI Client for Attractor REST API

## Overview

Eleven sprints of feature work have produced a powerful pipeline orchestration system with a complete REST API v1. While the API is fully documented and tested, interacting with it programmatically still requires direct HTTP tooling (`curl`, scripts). This sprint delivers a purpose-built Kotlin CLI that wraps the API and makes Attractor a first-class command-line citizen.

The CLI is built as a second fat JAR in the same Gradle project — no new repository, no new subproject root, no new dependencies. It reuses OkHttp (already present) for HTTP and kotlinx-serialization-json (already present) for JSON parsing. Argument parsing is implemented manually in idiomatic Kotlin — no picocli, no Commons CLI. The result is a self-contained, distributable `attractor-cli-<version>.jar` that can be run anywhere Java 17+ is available.

The CLI follows the **`attractor <resource> <verb> [args]`** command grammar familiar from tools like `kubectl`, `gh`, and `docker`. Output defaults to a human-readable table/text format, with `--output json` for machine-readable JSON (suitable for piping into `jq`). A global `--host` flag allows targeting any running Attractor instance.

The GitHub Actions CI and release workflows are extended (not replaced): CI now builds and tests the CLI alongside the server on every push/PR. The release workflow attaches the CLI JAR as an additional GitHub Release asset, so users can download it from the releases page alongside the server JAR.

## Use Cases

1. **CI/CD pipeline submission**: A CI script runs `java -jar attractor-cli.jar pipeline create --file my-pipeline.dot` and polls `java -jar attractor-cli.jar pipeline get $ID` until status is `completed`.

2. **Quick status check**: A developer runs `java -jar attractor-cli.jar pipeline list` to see all pipelines in a table — no browser needed.

3. **Scriptable lifecycle control**: An automation script pauses a running pipeline, inspects its stage log, and resumes it — all via CLI.

4. **DOT generation from prompt**: `java -jar attractor-cli.jar dot generate --prompt "Build and test a REST API"` generates a DOT pipeline and prints it to stdout for inspection or piping.

5. **Settings management**: `java -jar attractor-cli.jar settings set fireworks_enabled false` toggles a setting without opening the browser UI.

6. **Release artifact distribution**: A team member downloads `attractor-cli-v1.2.jar` from the GitHub Releases page and runs it against their Attractor server.

## Architecture

```
attractor-cli-<version>.jar
│
├── Main.kt                      (entry point, top-level dispatch)
│   ├── parse global flags: --host, --output, --help, --version
│   └── dispatch to: PipelineCommands | DotCommands | SettingsCommands
│                    ModelsCommand | EventsCommand
│
├── CliContext.kt                (shared config: host, outputFormat)
│
├── ApiClient.kt                 (OkHttp wrapper, all HTTP calls)
│   ├── get(path), post(path, body), patch(path, body), put(path, body), delete(path)
│   ├── postBinary(path, bytes, query) — for import
│   ├── getBinary(path) → ByteArray — for ZIP downloads
│   └── getStream(path) → Sequence<String> — for SSE
│
├── Formatter.kt                 (output: table vs json)
│   ├── printTable(headers, rows)
│   ├── printJson(json)
│   └── printError(message)
│
└── commands/
    ├── PipelineCommands.kt      (pipeline list|get|create|delete|rerun|pause|resume
    │                             cancel|archive|unarchive|stages|log|artifacts|
    │                             artifact|artifacts-zip|failure-report|export|import
    │                             family|iterate)
    ├── DotCommands.kt           (dot generate|validate|render|fix|iterate)
    ├── SettingsCommands.kt      (settings list|get|set)
    ├── ModelsCommand.kt         (models list)
    └── EventsCommand.kt         (events [id])

Command grammar:
  attractor [--host URL] [--output json|table] <resource> <verb> [options]

Examples:
  attractor pipeline list
  attractor pipeline get run-1700000000000-1
  attractor pipeline create --file my-pipeline.dot [--simulate] [--no-auto-approve]
  attractor pipeline pause run-1700000000000-1
  attractor pipeline log run-1700000000000-1 writeTests
  attractor pipeline artifacts run-1700000000000-1
  attractor pipeline export run-1700000000000-1 --output pipeline.zip
  attractor pipeline import pipeline.zip [--on-conflict skip|replace]
  attractor dot generate --prompt "Build a REST API pipeline"
  attractor dot validate --file my-pipeline.dot
  attractor settings list
  attractor settings set fireworks_enabled false
  attractor models list
  attractor events [pipeline-id]
```

### Fat JAR Build Strategy

A second `cliJar` Gradle task builds the CLI fat JAR with a different `Main-Class`:

```kotlin
// build.gradle.kts addition
tasks.register<Jar>("cliJar") {
    archiveClassifier.set("cli")
    manifest {
        attributes["Main-Class"] = "attractor.cli.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

The CLI `Main-Class` is `attractor.cli.MainKt`. Since this lives in the same source tree as the server, it compiles together — no separate module or source set configuration needed.

### HTTP Client Design

`ApiClient` wraps OkHttp and provides typed convenience methods. The server URL is passed in via `CliContext`. All non-2xx responses are converted to a `CliException` carrying the API error message. Exit code 1 on any error.

```kotlin
data class CliContext(val baseUrl: String, val outputFormat: OutputFormat)

enum class OutputFormat { TABLE, JSON }

class ApiClient(private val ctx: CliContext) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun get(path: String): String           // returns raw JSON string
    fun post(path: String, body: String = "{}"): String
    fun patch(path: String, body: String): String
    fun put(path: String, body: String): String
    fun delete(path: String): String
    fun getBinary(path: String): ByteArray
    fun postBinary(path: String, data: ByteArray, query: String = ""): String
    fun getStream(path: String): Sequence<String>  // yields SSE "data:" lines
}
```

### Argument Parsing Design

Arguments are parsed by a hand-written recursive dispatcher. No third-party library is needed because the grammar is simple: `resource verb [--flag value] [positional]`.

```kotlin
// Simplified pseudocode
fun main(args: Array<String>) {
    val (globals, remaining) = parseGlobals(args)
    val ctx = CliContext(globals.host, globals.output)
    when (remaining.firstOrNull()) {
        "pipeline" -> PipelineCommands(ctx).dispatch(remaining.drop(1))
        "dot"      -> DotCommands(ctx).dispatch(remaining.drop(1))
        "settings" -> SettingsCommands(ctx).dispatch(remaining.drop(1))
        "models"   -> ModelsCommand(ctx).dispatch(remaining.drop(1))
        "events"   -> EventsCommand(ctx).dispatch(remaining.drop(1))
        "--help", "-h", null -> printGlobalHelp(); exitProcess(0)
        "--version"          -> printVersion(); exitProcess(0)
        else -> { System.err.println("Unknown command: ${remaining.first()}"); exitProcess(1) }
    }
}
```

Each command class has a `dispatch(args)` that switches on the verb and calls a handler. Unknown subcommands print their group's help. `--help` at any level prints contextual help.

### Output Formatting

Default output (`--output table`) renders results as aligned ASCII tables. `--output json` passes the raw JSON response through unchanged, enabling `jq` piping.

```
$ attractor pipeline list
ID                          NAME              STATUS     STARTED
run-1700000000000-1         Autumn Falcon     completed  2026-02-27 10:00
run-1700000000001-2         Spring Hawk       running    2026-02-27 10:05

$ attractor pipeline list --output json
[{"id":"run-1700000000000-1","displayName":"Autumn Falcon",...}]
```

Error output always goes to stderr. Non-zero exit code on failure.

## Implementation Plan

### Phase 1: Build Infrastructure (~10%)

**Files:**
- `build.gradle.kts` — Modify (add `cliJar` task, add `cli` to `.PHONY`-equivalent)
- `Makefile` — Modify (add `cli-jar` target, update `help`)
- `.github/workflows/ci.yml` — Modify (add CLI build + test step)
- `.github/workflows/release.yml` — Modify (build CLI JAR + upload as release asset)

**Tasks:**
- [ ] Add `cliJar` Gradle task: `archiveClassifier = "cli"`, `Main-Class = "attractor.cli.MainKt"`
- [ ] Add `cli-jar` Makefile target: `$(GRADLEW) cliJar`; add to `help` output and `.PHONY`
- [ ] Extend CI workflow: add `./gradlew cliJar --no-daemon` step after existing build
- [ ] Extend release workflow: add `cliJar` to the build step; find the `*-cli.jar` and add to `files:` in the release action

**Verification:** `make cli-jar` succeeds; release workflow YAML is valid (lint); CI workflow includes CLI build.

---

### Phase 2: Core CLI Framework (~20%)

**Files:**
- `src/main/kotlin/attractor/cli/Main.kt` — Create
- `src/main/kotlin/attractor/cli/CliContext.kt` — Create
- `src/main/kotlin/attractor/cli/ApiClient.kt` — Create
- `src/main/kotlin/attractor/cli/Formatter.kt` — Create
- `src/main/kotlin/attractor/cli/CliException.kt` — Create

**Tasks:**
- [ ] `CliException.kt`: `class CliException(message: String, val exitCode: Int = 1) : Exception(message)`
- [ ] `CliContext.kt`: `data class CliContext(val baseUrl: String = "http://localhost:8080", val outputFormat: OutputFormat = OutputFormat.TABLE)`; `enum class OutputFormat { TABLE, JSON }`
- [ ] `ApiClient.kt`:
  - `fun get(path: String): String` — GET, assert 2xx, return body
  - `fun post(path: String, body: String = "{}"): String` — POST JSON
  - `fun patch(path: String, body: String): String`
  - `fun put(path: String, body: String): String`
  - `fun delete(path: String): String`
  - `fun getBinary(path: String): ByteArray` — for ZIP downloads
  - `fun postBinary(path: String, data: ByteArray, query: String = ""): String` — for pipeline import
  - `fun getStream(path: String): Sequence<String>` — reads SSE `data:` lines until closed
  - Non-2xx responses: parse `{"error":"..."}` and throw `CliException(apiError, 1)`
  - Connection failure: throw `CliException("Cannot connect to $baseUrl: ${e.message}", 1)`
- [ ] `Formatter.kt`:
  - `fun printTable(headers: List<String>, rows: List<List<String>>)` — column-width-aligned output
  - `fun printJson(json: String)` — prints raw JSON to stdout
  - `fun printError(message: String)` — prints to stderr
  - `fun printLine(text: String)` — prints to stdout (for plain-text logs, SSE deltas)
- [ ] `Main.kt`:
  - `fun main(args: Array<String>)` — parse `--host`, `--output`, `--help`, `--version`; dispatch to command groups
  - `fun printGlobalHelp()` — prints full command tree with one-line descriptions
  - Version from `MainKt::class.java.`package`.implementationVersion` (set by JAR manifest `Implementation-Version`)
  - `try { ... } catch (e: CliException) { System.err.println(e.message); exitProcess(e.exitCode) }`

---

### Phase 3: Pipeline Commands (~30%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` — Create

**Tasks:**

`class PipelineCommands(ctx: CliContext)` with `fun dispatch(args: List<String>)` switching on verb:

- [ ] `pipeline list` → `GET /api/v1/pipelines` → table: ID | Name | Status | Started
- [ ] `pipeline get <id>` → `GET /api/v1/pipelines/{id}` → key-value pairs or JSON; includes dotSource
- [ ] `pipeline create --file <path> [--simulate] [--no-auto-approve] [--prompt <text>]`
  - Read file; POST to `/api/v1/pipelines`; print `id` and `status`
  - `--simulate`: sets `simulate=true`; `--no-auto-approve`: sets `autoApprove=false`
- [ ] `pipeline delete <id>` → `DELETE /api/v1/pipelines/{id}` → print `deleted: true`
- [ ] `pipeline rerun <id>` → `POST /api/v1/pipelines/{id}/rerun` → print `status`
- [ ] `pipeline pause <id>` → `POST /api/v1/pipelines/{id}/pause` → print `paused: true`
- [ ] `pipeline resume <id>` → `POST /api/v1/pipelines/{id}/resume` → print `id` and `status`
- [ ] `pipeline cancel <id>` → `POST /api/v1/pipelines/{id}/cancel` → print `cancelled: true`
- [ ] `pipeline archive <id>` → `POST /api/v1/pipelines/{id}/archive` → print `archived: true`
- [ ] `pipeline unarchive <id>` → `POST /api/v1/pipelines/{id}/unarchive` → print `unarchived: true`
- [ ] `pipeline stages <id>` → `GET /api/v1/pipelines/{id}/stages` → table: # | Node | Status | Duration
- [ ] `pipeline log <id> <nodeId>` → `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` → print raw text
- [ ] `pipeline artifacts <id>` → `GET /api/v1/pipelines/{id}/artifacts` → table: Path | Size | Type
- [ ] `pipeline artifact <id> <path>` → `GET /api/v1/pipelines/{id}/artifacts/{path}` → print text to stdout
- [ ] `pipeline artifacts-zip <id> [--output <file>]` → download ZIP; default filename `artifacts-<id>.zip`
- [ ] `pipeline failure-report <id>` → `GET /api/v1/pipelines/{id}/failure-report` → print JSON
- [ ] `pipeline export <id> [--output <file>]` → download ZIP; default filename `pipeline-<id>.zip`
- [ ] `pipeline import <file> [--on-conflict skip|replace]` → POST binary; print status + id
- [ ] `pipeline family <id>` → `GET /api/v1/pipelines/{id}/family` → table: Ver | ID | Name | Status | Created
- [ ] `pipeline iterate <id> --file <dot> [--prompt <text>]` → POST iterations; print new id
- [ ] Per-verb `--help` / unknown verb → print `pipeline` group help

---

### Phase 4: DOT, Settings, Models, Events Commands (~15%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/DotCommands.kt` — Create
- `src/main/kotlin/attractor/cli/commands/SettingsCommands.kt` — Create
- `src/main/kotlin/attractor/cli/commands/ModelsCommand.kt` — Create
- `src/main/kotlin/attractor/cli/commands/EventsCommand.kt` — Create

**Tasks:**

**DotCommands:**
- [ ] `dot generate --prompt <text>` → `POST /api/v1/dot/generate` → print dotSource to stdout
- [ ] `dot validate --file <path>` → `POST /api/v1/dot/validate` → print valid/invalid + diagnostics
- [ ] `dot render --file <path> [--output <file>]` → `POST /api/v1/dot/render` → write SVG to file (default: `output.svg`) or stdout if `--output -`
- [ ] `dot fix --file <path> [--error <msg>]` → `POST /api/v1/dot/fix` → print fixed dotSource
- [ ] `dot iterate --file <path> --changes <text>` → `POST /api/v1/dot/iterate` → print new dotSource

**SettingsCommands:**
- [ ] `settings list` → `GET /api/v1/settings` → table: Key | Value
- [ ] `settings get <key>` → `GET /api/v1/settings/{key}` → print `key: value`
- [ ] `settings set <key> <value>` → `PUT /api/v1/settings/{key}` with `{"value":"..."}` → print updated value

**ModelsCommand:**
- [ ] `models list` → `GET /api/v1/models` → table: ID | Provider | Display Name | Context | Tools | Vision

**EventsCommand:**
- [ ] `events` → `GET /api/v1/events` SSE stream → print each `data:` payload as it arrives (Ctrl+C to stop)
- [ ] `events <id>` → `GET /api/v1/events/{id}` SSE stream → same; exits on pipeline completion if status = `completed|failed|cancelled`

---

### Phase 5: Tests (~25%)

**Files:**
- `src/test/kotlin/attractor/cli/ApiClientTest.kt` — Create
- `src/test/kotlin/attractor/cli/FormatterTest.kt` — Create
- `src/test/kotlin/attractor/cli/MainTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/SettingsCommandsTest.kt` — Create

**Fake HTTP server strategy:** Use JDK's `com.sun.net.httpserver.HttpServer` (already used by the main app!) to spin up a fake server on an ephemeral port in each test. This is perfectly hermetic — no OkHttp MockWebServer needed, no new deps.

```kotlin
// Test helper
fun fakeServer(handler: (HttpExchange) -> Unit): Pair<HttpServer, Int> {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/") { ex -> handler(ex); ex.close() }
    server.start()
    return server to server.address.port
}
```

**ApiClientTest tasks:**
- [ ] `GET /api/v1/pipelines` returns JSON → client returns raw JSON string
- [ ] Non-2xx response with `{"error":"not found","code":"NOT_FOUND"}` → throws `CliException` with message "not found"
- [ ] Connection refused → throws `CliException` with "Cannot connect to ..." message
- [ ] `getBinary` returns bytes from server response body
- [ ] `postBinary` sends raw bytes as request body

**FormatterTest tasks:**
- [ ] `printTable` aligns columns correctly for varying-width cell values
- [ ] `printTable` outputs header row with separator line
- [ ] `printTable` with empty rows prints header only

**MainTest tasks:**
- [ ] `--help` flag exits 0 and prints usage
- [ ] `--version` exits 0 and prints version string
- [ ] Unknown top-level command exits 1 with error to stderr
- [ ] Missing `--host` uses default `http://localhost:8080`
- [ ] `--host http://localhost:9090` overrides base URL

**PipelineCommandsTest tasks:**
- [ ] `pipeline list` makes GET to `/api/v1/pipelines` and prints table with expected headers
- [ ] `pipeline get <id>` makes GET to `/api/v1/pipelines/{id}` and prints key-value pairs
- [ ] `pipeline create --file <path>` reads file, POSTs to `/api/v1/pipelines`, prints ID
- [ ] `pipeline create` with missing `--file` exits 1 with usage error
- [ ] `pipeline pause <id>` makes POST to `/api/v1/pipelines/{id}/pause`
- [ ] `pipeline log <id> <nodeId>` makes GET and prints text response
- [ ] Unknown pipeline verb prints help and exits 1

**DotCommandsTest tasks:**
- [ ] `dot generate --prompt <text>` POSTs to `/api/v1/dot/generate` and prints dotSource
- [ ] `dot validate --file <path>` prints "valid" or "invalid" with diagnostics

**SettingsCommandsTest tasks:**
- [ ] `settings list` GETs `/api/v1/settings` and prints table
- [ ] `settings set <key> <value>` PUTs to `/api/v1/settings/{key}` with correct body

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `build.gradle.kts` | Modify | Add `cliJar` Gradle task with `attractor.cli.MainKt` main class |
| `Makefile` | Modify | Add `cli-jar` target + help entry; add to `.PHONY` |
| `.github/workflows/ci.yml` | Modify | Add CLI `cliJar` build step |
| `.github/workflows/release.yml` | Modify | Build CLI JAR + attach to GitHub Release |
| `src/main/kotlin/attractor/cli/Main.kt` | Create | Entry point, global flag parsing, command dispatch, version |
| `src/main/kotlin/attractor/cli/CliContext.kt` | Create | Shared config (host URL, output format) |
| `src/main/kotlin/attractor/cli/CliException.kt` | Create | Typed CLI error with exit code |
| `src/main/kotlin/attractor/cli/ApiClient.kt` | Create | OkHttp-based REST client; all HTTP methods + SSE |
| `src/main/kotlin/attractor/cli/Formatter.kt` | Create | Table/JSON output, stderr error printing |
| `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` | Create | All 20 pipeline subcommands |
| `src/main/kotlin/attractor/cli/commands/DotCommands.kt` | Create | 5 DOT operation subcommands |
| `src/main/kotlin/attractor/cli/commands/SettingsCommands.kt` | Create | 3 settings subcommands |
| `src/main/kotlin/attractor/cli/commands/ModelsCommand.kt` | Create | 1 models subcommand |
| `src/main/kotlin/attractor/cli/commands/EventsCommand.kt` | Create | SSE event stream commands |
| `src/test/kotlin/attractor/cli/ApiClientTest.kt` | Create | HTTP client unit tests with fake JDK server |
| `src/test/kotlin/attractor/cli/FormatterTest.kt` | Create | Table/JSON formatting unit tests |
| `src/test/kotlin/attractor/cli/MainTest.kt` | Create | Top-level flag parsing + dispatch tests |
| `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` | Create | Pipeline command unit tests |
| `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` | Create | DOT command unit tests |
| `src/test/kotlin/attractor/cli/commands/SettingsCommandsTest.kt` | Create | Settings command unit tests |

## Definition of Done

### Build & Packaging
- [ ] `make cli-jar` succeeds and produces `build/libs/coreys-attractor-<version>-cli.jar`
- [ ] `java -jar build/libs/coreys-attractor-*-cli.jar --help` exits 0 and prints usage with all top-level commands listed
- [ ] `java -jar build/libs/coreys-attractor-*-cli.jar --version` exits 0 and prints version
- [ ] `make help` output includes `cli-jar` target with description
- [ ] No new Gradle dependencies added to `build.gradle.kts`

### Commands — Pipeline
- [ ] `pipeline list` prints table with ID, Name, Status, Started columns
- [ ] `pipeline get <id>` prints full pipeline details
- [ ] `pipeline create --file <path>` creates and prints new pipeline ID
- [ ] `pipeline delete <id>` deletes pipeline and confirms
- [ ] `pipeline rerun|pause|resume|cancel|archive|unarchive <id>` all work with correct API calls
- [ ] `pipeline stages <id>` prints stage table
- [ ] `pipeline log <id> <nodeId>` prints raw log text
- [ ] `pipeline artifacts <id>` prints artifact list table
- [ ] `pipeline artifact <id> <path>` prints file content
- [ ] `pipeline artifacts-zip <id>` downloads ZIP to local file
- [ ] `pipeline failure-report <id>` prints failure JSON
- [ ] `pipeline export <id>` downloads ZIP to local file
- [ ] `pipeline import <file>` uploads ZIP, prints result
- [ ] `pipeline family <id>` prints family members table
- [ ] `pipeline iterate <id> --file <dot>` creates iteration

### Commands — DOT
- [ ] `dot generate --prompt <text>` prints generated DOT source
- [ ] `dot validate --file <path>` prints valid/invalid + diagnostics
- [ ] `dot render --file <path>` produces SVG file
- [ ] `dot fix --file <path>` prints fixed DOT
- [ ] `dot iterate --file <path> --changes <text>` prints modified DOT

### Commands — Settings, Models, Events
- [ ] `settings list` prints all settings as table
- [ ] `settings get <key>` prints single setting
- [ ] `settings set <key> <value>` updates setting and confirms
- [ ] `models list` prints models table
- [ ] `events` streams SSE events to stdout
- [ ] `events <id>` streams events for single pipeline

### Error Handling
- [ ] API error response → message printed to stderr, exit code 1
- [ ] Server unreachable → clear message to stderr, exit code 1
- [ ] Missing required argument → usage error to stderr, exit code 1
- [ ] `--output json` passes raw JSON through for all commands

### CI / Release
- [ ] CI workflow builds and tests CLI on push/PR to main
- [ ] Release workflow attaches `*-cli.jar` to GitHub Release on tag push
- [ ] All new tests pass with `./gradlew test`
- [ ] All existing tests continue to pass — zero regressions
- [ ] No compiler warnings

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| SSE stream reading blocks indefinitely | Low | Medium | Use OkHttp's `ResponseBody.byteStream()` with `BufferedReader`; document Ctrl+C exit; `events <id>` auto-exits when status = terminal |
| Large `artifacts.zip` or `export` ZIP held in memory | Medium | Low | Use `getBinary()` only (already reads full response); acceptable for CLI use — files expected to be <100MB |
| `--output json` for binary commands (zip download) | Low | Low | Binary commands ignore `--output json` and always write to a file; document clearly |
| Table formatting breaks on very long IDs or names | Low | Low | Column widths computed per-render; truncate cells at 40 chars with `...` suffix |
| Same-source compilation: CLI code can import server internals | Medium | Medium | CLI code must only import its own `attractor.cli.*` classes; add a note in the sprint — a future sprint can enforce this with source sets if needed |
| `Implementation-Version` manifest attribute not populated | Low | Low | Add `attributes["Implementation-Version"] = version` to `cliJar` task manifest |

## Security Considerations

- CLI sends requests over HTTP (not HTTPS) by default — acceptable for localhost use; HTTPS support deferred to a future sprint when TLS support is added to the server.
- No credentials are stored by the CLI — API keys are not required (the server handles them).
- `pipeline import` reads a local file and POSTs raw bytes — no shell injection risk.
- `--host` value is used as a URL prefix, not passed to a shell — no injection risk.

## Dependencies

- Sprint 010 (completed): REST API v1 and `docs/api/rest-v1.md` must exist
- Sprint 011 (in_progress): Settings extension adds new keys that `settings list` will surface automatically

## Open Questions

1. **Command binary name**: Should the JAR be invocable as `attractor` after installation? (Propose a simple shell wrapper script in a future sprint, not in scope here — JAR invoked via `java -jar`.)
2. **`--watch` flag for `pipeline get`**: Should there be a `--follow` option that polls until the pipeline reaches a terminal state? Proposed: add `pipeline watch <id>` to Phase 3 as a stretch goal.
3. **SSE events exit strategy**: `events <id>` exits automatically when the pipeline reaches `completed|failed|cancelled`. `events` (global) requires Ctrl+C. Is this acceptable?
4. **`dot generate --stream`**: Should there be a streaming variant that shows tokens as they arrive? Proposed: add as stretch goal; default `dot generate` blocks and shows the complete output.
