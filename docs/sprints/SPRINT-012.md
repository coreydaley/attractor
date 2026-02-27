# Sprint 012: Kotlin CLI Client for Attractor REST API

## Overview

Eleven sprints of feature work have produced a powerful pipeline orchestration system with a browser UI and a complete REST API v1 (35 endpoints). While the API is fully documented and tested, interacting with it programmatically still requires direct HTTP tooling (`curl`, scripts) or opening the web UI. This sprint delivers a purpose-built Kotlin CLI that wraps the API and makes Attractor a first-class command-line citizen — scriptable, discoverable, and directly distributable.

The CLI is built as a second fat JAR in the same Gradle project: no new repository, no new subproject, no new runtime dependencies. It reuses OkHttp (already present) for HTTP and kotlinx-serialization-json (already present) for JSON parsing. Argument parsing is implemented manually in idiomatic Kotlin — no picocli, no Commons CLI. The result is a self-contained `attractor-cli-<version>.jar` runnable anywhere Java 21+ is available, plus a `bin/attractor` shell wrapper so users can add it to their PATH.

The CLI follows the **`attractor <resource> <verb> [options]`** grammar familiar from `kubectl`, `gh`, and `docker`. Output defaults to human-readable table/text, with `--output json` for machine-readable JSON suitable for piping into `jq`. A global `--host` flag allows targeting any running Attractor instance. All 35 REST API v1 endpoints are accessible via CLI commands.

The GitHub Actions CI and release workflows are extended (not replaced): CI builds and tests the CLI alongside the server on every push/PR. The release workflow attaches `attractor-cli-<version>.jar` as a dedicated GitHub Release asset, so users can download it alongside the server JAR from the releases page.

## Use Cases

1. **CI/CD pipeline submission**: A CI script runs `attractor pipeline create --file my-pipeline.dot` and polls `attractor pipeline watch $ID` until the pipeline reaches a terminal state.

2. **Quick operator status check**: A developer runs `attractor pipeline list` to see all pipelines as a table — no browser needed.

3. **Scriptable lifecycle control**: An automation script uses `--output json` output to pause, inspect, and resume pipelines in a Bash loop.

4. **DOT generation from terminal**: `attractor dot generate --prompt "Build and test a REST API"` generates a DOT pipeline and prints it to stdout for inspection or piping to a file.

5. **Settings management**: `attractor settings set fireworks_enabled false` toggles a setting without opening the browser UI.

6. **Release artifact distribution**: A team member downloads `attractor-cli-v1.2.jar` from the GitHub Releases page and runs it against their Attractor server with `java -jar attractor-cli-v1.2.jar --host http://prod-server:8080 pipeline list`.

7. **Streaming DOT generation**: `attractor dot generate-stream --prompt "..." ` prints LLM tokens as they arrive, providing interactive feedback.

## Architecture

```
attractor-cli-<version>.jar (Main-Class: attractor.cli.CliMainKt)
bin/attractor                (shell wrapper → java -jar attractor-cli-*.jar "$@")
│
├── CliMain.kt               entry point, global flag parsing, top-level dispatch
├── CliContext.kt            shared config: host, outputFormat
├── CliException.kt          CliException(message, exitCode=1|2)
├── ApiClient.kt             OkHttp wrapper: get/post/patch/put/delete/getBinary/postBinary/getStream
├── Formatter.kt             printTable/printJson/printLine/printError
│
└── commands/
    ├── PipelineCommands.kt  pipeline list/get/create/update/delete/rerun/pause/resume/
    │                        cancel/archive/unarchive/stages/watch/iterate/family
    ├── ArtifactCommands.kt  artifact list/get/download-zip/stage-log/failure-report/export/import
    ├── DotCommands.kt       dot generate/generate-stream/validate/render/fix/fix-stream/
    │                        iterate/iterate-stream
    ├── SettingsCommands.kt  settings list/get/set
    ├── ModelsCommand.kt     models list
    └── EventsCommand.kt     events [id]

Command grammar:
  attractor [--host <url>] [--output <text|json>] [--help] [--version]
            <resource> <verb> [--flag <value>] [positional...]

Exit codes:
  0  success
  1  API error / connection error / runtime error
  2  usage error (missing arg, unknown command, invalid flag)
```

### Command Surface (1:1 with REST API v1)

```
pipeline:
  list
  get <id>
  create --file <path> [--name <name>] [--simulate] [--no-auto-approve] [--prompt <text>]
  update <id> [--file <path>] [--prompt <text>]
  delete <id>
  rerun <id>
  pause <id>
  resume <id>
  cancel <id>
  archive <id>
  unarchive <id>
  stages <id>
  watch <id> [--interval-ms <n>] [--timeout-ms <n>]
  iterate <id> --file <path> [--prompt <text>]
  family <id>

artifact:
  list <id>
  get <id> <path>
  download-zip <id> [--output <file>]       default: artifacts-<id>.zip
  stage-log <id> <nodeId>
  failure-report <id>
  export <id> [--output <file>]             default: pipeline-<id>.zip
  import <file> [--on-conflict skip|overwrite]

dot:
  generate --prompt <text>
  generate-stream --prompt <text>
  validate --file <path>
  render --file <path> [--output <file>]    default: output.svg
  fix --file <path> [--error <msg>]
  fix-stream --file <path> [--error <msg>]
  iterate --file <path> --changes <text>
  iterate-stream --file <path> --changes <text>

settings:
  list
  get <key>
  set <key> <value>

models:
  list

events [<id>]
```

### Fat JAR Build Strategy

A `cliJar` Gradle task builds the CLI fat JAR with an explicit artifact name and `Main-Class`:

```kotlin
tasks.register<Jar>("cliJar") {
    archiveBaseName.set("attractor-cli")
    manifest {
        attributes["Main-Class"] = "attractor.cli.CliMainKt"
        attributes["Implementation-Version"] = version
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

Output: `build/libs/attractor-cli-<version>.jar`

### Shell Wrapper

`bin/attractor`:
```bash
#!/usr/bin/env bash
JAR="$(dirname "$0")/../build/libs/attractor-cli-*.jar"
exec java -jar $JAR "$@"
```

Users who want `attractor` on their PATH can copy the JAR and wrapper to `~/.local/bin/` or add the project's `bin/` to `PATH`.

### HTTP Client Design

`ApiClient` wraps OkHttp. Non-2xx responses parse `{"error":"..."}` and throw `CliException`. Connection failures throw `CliException("Cannot connect to $baseUrl: ...", 1)`.

### Argument Parsing Design

Hand-written recursive dispatcher — no library. Grammar is simple: `resource verb [--flag value] [positional]`. Each command class has a `dispatch(args: List<String>)` that switches on verb. Unknown verbs and missing required flags print contextual help and exit 2. `--help` at any level works.

### Output Formatting

Default (`--output text`): aligned ASCII tables with header separator. `--output json`: raw JSON passthrough (enables `jq` piping). Errors always go to stderr. Binary commands (ZIP downloads, SVG render) write to files — never to stdout when binary output would corrupt pipes.

## Implementation Plan

### Phase 1: Build Infrastructure (~10%)

**Files:**
- `build.gradle.kts` — Modify (add `cliJar` task)
- `Makefile` — Modify (add `cli-jar` target + help entry)
- `.github/workflows/ci.yml` — Modify (add CLI build step)
- `.github/workflows/release.yml` — Modify (build + upload CLI JAR)

**Tasks:**
- [ ] Add `cliJar` Gradle task: `archiveBaseName = "attractor-cli"`, `Main-Class = "attractor.cli.CliMainKt"`, `Implementation-Version = version`
- [ ] Add `cli-jar` to Makefile `.PHONY` and `help` output: `make cli-jar → Build CLI fat JAR`
- [ ] Extend CI workflow: add `./gradlew cliJar --no-daemon` step after the existing build step
- [ ] Extend release workflow:
  - Step 1: build both JARs (`./gradlew jar cliJar --no-daemon`)
  - Step 2: discover server JAR path → `steps.find-server-jar.outputs.jar_path`
  - Step 3: discover CLI JAR path → `steps.find-cli-jar.outputs.jar_path`
  - Step 4: pass both paths to `softprops/action-gh-release@v2` `files:` (newline-separated)
- [ ] Add `bin/attractor` shell wrapper script (executable)

---

### Phase 2: Core CLI Framework (~20%)

**Files:**
- `src/main/kotlin/attractor/cli/Main.kt` — Create
- `src/main/kotlin/attractor/cli/CliContext.kt` — Create
- `src/main/kotlin/attractor/cli/CliException.kt` — Create
- `src/main/kotlin/attractor/cli/ApiClient.kt` — Create
- `src/main/kotlin/attractor/cli/Formatter.kt` — Create

**Tasks:**
- [ ] `CliException.kt`: `class CliException(message: String, val exitCode: Int = 1) : Exception(message)`
- [ ] `CliContext.kt`: `data class CliContext(val baseUrl: String = "http://localhost:8080", val outputFormat: OutputFormat = TABLE)` + `enum class OutputFormat { TEXT, JSON }`
- [ ] `ApiClient.kt`:
  - `fun get(path: String): String` — GET, assert 2xx, return body string
  - `fun post(path: String, body: String = "{}"): String`
  - `fun patch(path: String, body: String): String`
  - `fun put(path: String, body: String): String`
  - `fun delete(path: String): String`
  - `fun getBinary(path: String): ByteArray` — for ZIP/SVG downloads
  - `fun postBinary(path: String, data: ByteArray, query: String = ""): String` — for pipeline import
  - `fun getStream(path: String): Sequence<String>` — reads SSE `data:` lines until stream closes
  - Non-2xx: parse `{"error":"..."}` → throw `CliException(apiError, 1)`
  - Connection failure: throw `CliException("Cannot connect to $baseUrl: ${e.message}", 1)`
- [ ] `Formatter.kt`:
  - `fun printTable(headers: List<String>, rows: List<List<String>>)` — column-width-aligned with header separator
  - `fun printJson(json: String)` — prints raw JSON to stdout
  - `fun printLine(text: String)` — stdout plain text
  - `fun printError(message: String)` — stderr
  - Column truncation: cap at 40 chars with `...` suffix to prevent table wrap
- [ ] `Main.kt`:
  - Parse `--host`, `--output text|json`, `--help`, `--version` before dispatch
  - Dispatch to: `PipelineCommands`, `ArtifactCommands`, `DotCommands`, `SettingsCommands`, `ModelsCommand`, `EventsCommand`
  - Unknown resource → stderr + exit 2; `null` resource → print help + exit 0
  - `try { dispatch() } catch (e: CliException) { System.err.println(e.message); exitProcess(e.exitCode) }`
  - `--version` prints `attractor-cli <version>` from JAR manifest `Implementation-Version`

---

### Phase 3: Pipeline Commands (~20%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` — Create

**Tasks:**
- [ ] `pipeline list` → `GET /api/v1/pipelines` → table: ID | Name | Status | Started
- [ ] `pipeline get <id>` → `GET /api/v1/pipelines/{id}` → key-value pairs (text) or JSON
- [ ] `pipeline create --file <path> [--name <name>] [--simulate] [--no-auto-approve] [--prompt <text>]`
  - Read DOT file; POST `{"dotSource","fileName","simulate","autoApprove","originalPrompt"}`; print ID + status
  - Missing `--file` → exit 2 with usage error
- [ ] `pipeline update <id> [--file <path>] [--prompt <text>]` → `PATCH /api/v1/pipelines/{id}` → print updated ID
- [ ] `pipeline delete <id>` → `DELETE /api/v1/pipelines/{id}` → print `deleted: true`
- [ ] `pipeline rerun <id>` → `POST /api/v1/pipelines/{id}/rerun` → print new status
- [ ] `pipeline pause <id>` → `POST /api/v1/pipelines/{id}/pause` → print `paused: true`
- [ ] `pipeline resume <id>` → `POST /api/v1/pipelines/{id}/resume` → print new ID + status
- [ ] `pipeline cancel <id>` → `POST /api/v1/pipelines/{id}/cancel` → print `cancelled: true`
- [ ] `pipeline archive <id>` → `POST /api/v1/pipelines/{id}/archive` → print `archived: true`
- [ ] `pipeline unarchive <id>` → `POST /api/v1/pipelines/{id}/unarchive` → print `unarchived: true`
- [ ] `pipeline stages <id>` → `GET /api/v1/pipelines/{id}/stages` → table: # | Node | Status | Duration | Error
- [ ] `pipeline watch <id> [--interval-ms <n>] [--timeout-ms <n>]`
  - Poll `GET /api/v1/pipelines/{id}` every `interval-ms` (default 2000) ms
  - Print status line each poll: `[HH:MM:SS] status: running | current: writeTests`
  - Exit 0 when status = `completed`; exit 1 when `failed`/`cancelled`
  - Exit 1 with timeout error if `--timeout-ms` exceeded (default: no timeout)
- [ ] `pipeline iterate <id> --file <path> [--prompt <text>]` → `POST /api/v1/pipelines/{id}/iterations` → print new ID + familyId
- [ ] `pipeline family <id>` → `GET /api/v1/pipelines/{id}/family` → table: Ver | ID | Name | Status | Created
- [ ] Unknown verb → print pipeline help, exit 2

---

### Phase 4: Artifact Commands (~10%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/ArtifactCommands.kt` — Create

**Tasks:**
- [ ] `artifact list <id>` → `GET /api/v1/pipelines/{id}/artifacts` → table: Path | Size | Type
- [ ] `artifact get <id> <path>` → `GET /api/v1/pipelines/{id}/artifacts/{path}` → print text content to stdout
- [ ] `artifact download-zip <id> [--output <file>]` → `GET /api/v1/pipelines/{id}/artifacts.zip`
  - Default filename: `artifacts-<id>.zip`; write to current directory
  - Print `Saved to artifacts-<id>.zip (N bytes)`
- [ ] `artifact stage-log <id> <nodeId>` → `GET /api/v1/pipelines/{id}/stages/{nodeId}/log` → print text to stdout
- [ ] `artifact failure-report <id>` → `GET /api/v1/pipelines/{id}/failure-report` → print JSON
- [ ] `artifact export <id> [--output <file>]` → `GET /api/v1/pipelines/{id}/export`
  - Default filename: `pipeline-<id>.zip`
  - Print `Saved to pipeline-<id>.zip (N bytes)`
- [ ] `artifact import <file> [--on-conflict skip|overwrite]` → `POST /api/v1/pipelines/import`
  - Read file bytes; set `Content-Type: application/zip`; pass `?onConflict=skip|overwrite`
  - Print `status: started | id: ...` or `status: skipped`

---

### Phase 5: DOT, Settings, Models, Events Commands (~15%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/DotCommands.kt` — Create
- `src/main/kotlin/attractor/cli/commands/SettingsCommands.kt` — Create
- `src/main/kotlin/attractor/cli/commands/ModelsCommand.kt` — Create
- `src/main/kotlin/attractor/cli/commands/EventsCommand.kt` — Create

**DotCommands tasks:**
- [ ] `dot generate --prompt <text>` → `POST /api/v1/dot/generate` → print dotSource to stdout
- [ ] `dot generate-stream --prompt <text>` → `GET /api/v1/dot/generate/stream?prompt=<encoded>`
  - Print each delta token as it arrives; final line prints full dotSource on `"done":true` event
- [ ] `dot validate --file <path>` → `POST /api/v1/dot/validate` → print `valid` or `invalid` + diagnostics table
- [ ] `dot render --file <path> [--output <file>]` → `POST /api/v1/dot/render`
  - Default output file: `output.svg`; print `Saved to output.svg`
- [ ] `dot fix --file <path> [--error <msg>]` → `POST /api/v1/dot/fix` → print fixed dotSource
- [ ] `dot fix-stream --file <path> [--error <msg>]` → `GET /api/v1/dot/fix/stream?dotSource=...&error=...`
  - Stream tokens; print full dotSource on `"done":true`
- [ ] `dot iterate --file <path> --changes <text>` → `POST /api/v1/dot/iterate` → print new dotSource
- [ ] `dot iterate-stream --file <path> --changes <text>` → `GET /api/v1/dot/iterate/stream?baseDot=...&changes=...`
  - Stream tokens; print full dotSource on `"done":true`

**SettingsCommands tasks:**
- [ ] `settings list` → `GET /api/v1/settings` → table: Key | Value
- [ ] `settings get <key>` → `GET /api/v1/settings/{key}` → print `key: value`
- [ ] `settings set <key> <value>` → `PUT /api/v1/settings/{key}` with `{"value":"..."}` → print `key: value`

**ModelsCommand tasks:**
- [ ] `models list` → `GET /api/v1/models` → table: ID | Provider | Name | Context | Tools | Vision

**EventsCommand tasks:**
- [ ] `events` → `GET /api/v1/events` SSE stream → print each `data:` payload on arrival; runs until Ctrl+C
- [ ] `events <id>` → `GET /api/v1/events/{id}` SSE stream → same; exits automatically when pipeline status = `completed|failed|cancelled`

---

### Phase 6: Tests (~25%)

**Files:**
- `src/test/kotlin/attractor/cli/ApiClientTest.kt` — Create
- `src/test/kotlin/attractor/cli/FormatterTest.kt` — Create
- `src/test/kotlin/attractor/cli/MainTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` — Create
- `src/test/kotlin/attractor/cli/commands/SettingsCommandsTest.kt` — Create

**Fake HTTP server strategy:** Use JDK's `com.sun.net.httpserver.HttpServer` (same as the main app's web server) on ephemeral port 0. No new deps; perfectly hermetic.

```kotlin
// Test helper (shared across test files)
fun fakeSever(handler: HttpHandler): Pair<HttpServer, Int> {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/") { ex -> handler.handle(ex) }
    server.start()
    return server to server.address.port
}
// Usage:
// val (srv, port) = fakeServer { ex ->
//     ex.sendResponseHeaders(200, json.length.toLong())
//     ex.responseBody.writer().use { it.write(json) }
// }
// try { ... } finally { srv.stop(0) }
```

**ApiClientTest tasks:**
- [ ] `GET /api/v1/pipelines` returns JSON body → client returns raw JSON string
- [ ] Non-2xx `{"error":"not found","code":"NOT_FOUND"}` → throws `CliException("not found", 1)`
- [ ] Connection refused → throws `CliException("Cannot connect to ...", 1)`
- [ ] `getBinary` returns correct bytes from server body
- [ ] `postBinary` sends raw bytes as request body (verify via fake server body read)
- [ ] `getStream` yields SSE `data:` line payloads from server response

**FormatterTest tasks:**
- [ ] `printTable` aligns all columns correctly for varying-width cells
- [ ] `printTable` outputs header row + separator line before data rows
- [ ] `printTable` with empty rows prints header only (no crash)
- [ ] Cells longer than 40 chars are truncated to `...`-suffixed 40-char string

**MainTest tasks:**
- [ ] `--help` exits 0 and prints top-level usage including all resource groups
- [ ] `--version` exits 0 and prints version string
- [ ] Unknown top-level resource exits 2 with error to stderr
- [ ] Missing resource exits 0 and prints help
- [ ] `--host http://other:9090` overrides base URL passed to commands
- [ ] `--output json` passes `OutputFormat.JSON` to command context

**PipelineCommandsTest tasks:**
- [ ] `pipeline list` makes `GET /api/v1/pipelines`; prints table with ID, Name, Status, Started headers
- [ ] `pipeline get <id>` makes `GET /api/v1/pipelines/{id}`; prints key-value pairs
- [ ] `pipeline create --file <path>` reads file; POSTs correct JSON; prints ID + status
- [ ] `pipeline create` without `--file` exits 2 with usage error
- [ ] `pipeline update <id> --file <path>` PATCHes correct endpoint
- [ ] `pipeline pause <id>` POSTs to `.../pause`; prints `paused: true`
- [ ] `pipeline watch <id>` polls until status `completed`; exits 0
- [ ] `pipeline watch <id>` exits 1 when status becomes `failed`
- [ ] Unknown pipeline verb exits 2 and prints pipeline help

**ArtifactCommandsTest tasks:**
- [ ] `artifact list <id>` GETs artifacts endpoint; prints table
- [ ] `artifact download-zip <id>` downloads bytes; writes to derived filename; prints confirmation
- [ ] `artifact import <file>` posts binary data with correct Content-Type

**DotCommandsTest tasks:**
- [ ] `dot generate --prompt <text>` POSTs to `/api/v1/dot/generate`; prints dotSource
- [ ] `dot validate --file <path>` POSTs and prints `valid` or `invalid` with diagnostics
- [ ] `dot generate-stream --prompt <text>` reads SSE stream and prints deltas

**SettingsCommandsTest tasks:**
- [ ] `settings list` GETs `/api/v1/settings`; prints table
- [ ] `settings set <key> <value>` PUTs to `/api/v1/settings/{key}` with `{"value":"..."}`

---

### Phase 7: Documentation (~5%)

**Files:**
- `README.md` — Modify (add CLI quickstart section)

**Tasks:**
- [ ] Add "CLI Usage" section to `README.md`:
  - Build: `make cli-jar`
  - Run: `java -jar build/libs/attractor-cli-<version>.jar --help`
  - Examples for common commands: `pipeline list`, `pipeline create`, `pipeline watch`, `dot generate`
  - Global flags: `--host`, `--output`
  - Shell wrapper: `bin/attractor` usage
  - Link to `docs/api/rest-v1.md` for full API reference

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `build.gradle.kts` | Modify | Add `cliJar` Gradle task with `attractor-cli` base name and `CliMainKt` entry point |
| `Makefile` | Modify | Add `cli-jar` target + help entry; add to `.PHONY` |
| `bin/attractor` | Create | Shell wrapper that invokes the CLI JAR |
| `.github/workflows/ci.yml` | Modify | Add `./gradlew cliJar` build step |
| `.github/workflows/release.yml` | Modify | Build both JARs; discover paths separately; upload both as release assets |
| `src/main/kotlin/attractor/cli/Main.kt` | Create | Entry point, global flag parsing, top-level dispatch, `--version` |
| `src/main/kotlin/attractor/cli/CliContext.kt` | Create | Shared config (host URL, output format enum) |
| `src/main/kotlin/attractor/cli/CliException.kt` | Create | Typed CLI error with exit code (1=runtime, 2=usage) |
| `src/main/kotlin/attractor/cli/ApiClient.kt` | Create | OkHttp-backed REST client: JSON/binary/SSE stream methods |
| `src/main/kotlin/attractor/cli/Formatter.kt` | Create | Table/JSON output, stderr error printing, cell truncation |
| `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` | Create | 15 pipeline subcommands including `watch` |
| `src/main/kotlin/attractor/cli/commands/ArtifactCommands.kt` | Create | 7 artifact subcommands; binary write with derived filenames |
| `src/main/kotlin/attractor/cli/commands/DotCommands.kt` | Create | 8 DOT subcommands including 3 streaming variants |
| `src/main/kotlin/attractor/cli/commands/SettingsCommands.kt` | Create | 3 settings subcommands |
| `src/main/kotlin/attractor/cli/commands/ModelsCommand.kt` | Create | Models list subcommand |
| `src/main/kotlin/attractor/cli/commands/EventsCommand.kt` | Create | Global and per-pipeline SSE stream commands |
| `src/test/kotlin/attractor/cli/ApiClientTest.kt` | Create | HTTP client unit tests with fake JDK HttpServer |
| `src/test/kotlin/attractor/cli/FormatterTest.kt` | Create | Table formatting and JSON passthrough unit tests |
| `src/test/kotlin/attractor/cli/MainTest.kt` | Create | Top-level flag parsing, dispatch, exit code unit tests |
| `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` | Create | Pipeline command unit tests (15 verbs) |
| `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` | Create | Artifact command unit tests |
| `src/test/kotlin/attractor/cli/commands/DotCommandsTest.kt` | Create | DOT command unit tests |
| `src/test/kotlin/attractor/cli/commands/SettingsCommandsTest.kt` | Create | Settings command unit tests |
| `README.md` | Modify | Add CLI quickstart section with build/run/examples |

## Definition of Done

### Build & Packaging
- [ ] `make cli-jar` produces `build/libs/attractor-cli-<version>.jar`
- [ ] `java -jar build/libs/attractor-cli-*.jar --help` exits 0 and prints all resource groups
- [ ] `java -jar build/libs/attractor-cli-*.jar --version` exits 0 and prints version string
- [ ] `bin/attractor` exists, is executable, and invokes the JAR correctly
- [ ] `make help` lists `cli-jar` with a one-line description
- [ ] No new Gradle dependencies added to `build.gradle.kts`

### Coverage — All 35 REST v1 Endpoints
- [ ] `pipeline list` → `GET /api/v1/pipelines`
- [ ] `pipeline get <id>` → `GET /api/v1/pipelines/{id}`
- [ ] `pipeline create --file <path>` → `POST /api/v1/pipelines`
- [ ] `pipeline update <id>` → `PATCH /api/v1/pipelines/{id}`
- [ ] `pipeline delete <id>` → `DELETE /api/v1/pipelines/{id}`
- [ ] `pipeline rerun|pause|resume|cancel|archive|unarchive <id>` → corresponding POST endpoints
- [ ] `pipeline iterate <id>` → `POST /api/v1/pipelines/{id}/iterations`
- [ ] `pipeline family <id>` → `GET /api/v1/pipelines/{id}/family`
- [ ] `pipeline stages <id>` → `GET /api/v1/pipelines/{id}/stages`
- [ ] `artifact stage-log <id> <nodeId>` → `GET /api/v1/pipelines/{id}/stages/{nodeId}/log`
- [ ] `artifact list <id>` → `GET /api/v1/pipelines/{id}/artifacts`
- [ ] `artifact get <id> <path>` → `GET /api/v1/pipelines/{id}/artifacts/{path}`
- [ ] `artifact download-zip <id>` → `GET /api/v1/pipelines/{id}/artifacts.zip`
- [ ] `artifact failure-report <id>` → `GET /api/v1/pipelines/{id}/failure-report`
- [ ] `artifact export <id>` → `GET /api/v1/pipelines/{id}/export`
- [ ] `artifact import <file>` → `POST /api/v1/pipelines/import`
- [ ] `dot render --file` → `POST /api/v1/dot/render`
- [ ] `dot validate --file` → `POST /api/v1/dot/validate`
- [ ] `dot generate --prompt` → `POST /api/v1/dot/generate`
- [ ] `dot generate-stream --prompt` → `GET /api/v1/dot/generate/stream`
- [ ] `dot fix --file` → `POST /api/v1/dot/fix`
- [ ] `dot fix-stream --file` → `GET /api/v1/dot/fix/stream`
- [ ] `dot iterate --file` → `POST /api/v1/dot/iterate`
- [ ] `dot iterate-stream --file` → `GET /api/v1/dot/iterate/stream`
- [ ] `settings list` → `GET /api/v1/settings`
- [ ] `settings get <key>` → `GET /api/v1/settings/{key}`
- [ ] `settings set <key> <value>` → `PUT /api/v1/settings/{key}`
- [ ] `models list` → `GET /api/v1/models`
- [ ] `events` → `GET /api/v1/events`
- [ ] `events <id>` → `GET /api/v1/events/{id}`

### Convenience Commands
- [ ] `pipeline watch <id>` polls until terminal state; exits 0=completed, 1=failed/cancelled
- [ ] `pipeline watch --timeout-ms <n>` exits 1 on timeout

### Error Handling & Output
- [ ] API error → message to stderr, exit 1
- [ ] Server unreachable → "Cannot connect to ..." to stderr, exit 1
- [ ] Missing required arg → usage message to stderr, exit 2
- [ ] Unknown command → usage message to stderr, exit 2
- [ ] `--output json` passes raw JSON to stdout for all JSON-capable commands
- [ ] Binary downloads write to auto-derived filename with confirmation message
- [ ] Binary downloads accept `--output <file>` override

### CI / Release
- [ ] CI workflow builds CLI JAR on every push/PR to main
- [ ] Release workflow uploads both `coreys-attractor-*.jar` (server) and `attractor-cli-*.jar` (CLI) as GitHub Release assets on `v*` tag push
- [ ] All new tests pass with `make test`
- [ ] All existing tests continue to pass — zero regressions
- [ ] No compiler warnings
- [ ] Java 21 throughout (build, CI, release)

### Documentation
- [ ] `README.md` has a "CLI Usage" section with build, run, and example commands
- [ ] `--help` output at top level and per resource group is accurate and complete

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| SSE stream for events blocks indefinitely | Low | Medium | OkHttp reads stdout as `BufferedReader`; Ctrl+C (SIGINT) closes the JVM and terminates the connection cleanly |
| `pipeline watch` infinite loop if server restarts mid-poll | Low | Low | Add `--timeout-ms` default of no timeout; document that Ctrl+C exits; retry once on connection error before exiting 1 |
| Gradle fat JAR collision: CLI JAR overrides server JAR | None | High | Different `archiveBaseName` (`attractor-cli` vs `coreys-attractor`); tasks are independent |
| Binary zip kept in memory before write | Low | Low | ZIP files for typical pipelines are small (<100MB); `getBinary()` reads full response; acceptable for CLI use |
| Same source tree: CLI code can import server internals | Medium | Medium | CLI code only imports `attractor.cli.*`; note in DoD; enforce via code review; formal source-set isolation deferred |
| `bin/attractor` glob `attractor-cli-*.jar` matches multiple JARs if stale builds exist | Low | Low | Script uses `ls -t ... | head -1` to pick newest; document `make clean` before fresh install |
| Release workflow uploads partial assets on build failure | Low | High | Both JARs built in one Gradle step; if either fails, the step fails and release action is not reached |

## Security Considerations

- CLI communicates over HTTP (localhost by default). HTTPS support deferred until server-side TLS is added.
- No credentials are stored by the CLI. API keys are managed entirely server-side.
- `artifact import` reads a local file and POSTs raw bytes — no shell execution, no injection risk.
- `--host` is used as a URL prefix only, not passed to a shell.
- Binary output commands write to explicit file paths only — never to arbitrary paths via `--output`; no path traversal risk in CLI output logic.

## Dependencies

- Sprint 010 (completed): REST API v1 endpoints and `docs/api/rest-v1.md` spec
- Sprint 011 (in_progress): Settings extensions will surface automatically via `settings list` once live — no CLI changes needed

## Open Questions

1. **Formal source-set isolation**: Should CLI code be enforced to not import server internals via a separate Gradle source set or module? (Proposed: enforce via code review in this sprint; add formal isolation in a future build hygiene sprint.)
2. **`pipeline watch` verbose mode**: Should each poll print the full stages table, or just a one-line status summary? (Proposed: one-line summary; `--verbose` flag can be added if requested.)
3. **Global SSE `events` stream format**: When the server broadcasts JSON `{"pipelines":[...]}`, should the CLI pretty-print or pass raw JSON? (Proposed: pass raw JSON with `--output json`; print a single `Updated: N pipelines` summary line in text mode.)
