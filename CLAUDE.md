# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

**Requirements:** Java 25 (Gradle 9.4.0), `git`, `graphviz` (`dot`)

```bash
make build          # compile and assemble
make run            # start web UI on port 7070 (via Gradle, picks up source changes)
make run-jar        # run pre-built fat JAR (faster startup)
make dev            # watch src/, rebuild + restart on change (requires entr)
make test           # run the full test suite
make check          # tests + all static checks
make clean          # delete build output
```

Run a single test class via Gradle:
```bash
./gradlew test --tests "attractor.engine.EngineTest"
```

Override `JAVA_HOME` if Java 25 is not at the default Homebrew path:
```bash
make build JAVA_HOME=/usr/lib/jvm/java-25-openjdk
```

The server JAR is `build/libs/attractor-server-devel.jar`; the CLI JAR is `build/libs/attractor-cli-devel.jar`. Use `make release` to produce versioned JARs from a git tag.

**Docker:**
```bash
make docker-build-base   # builds attractor-base:local from Dockerfile.base
make docker-build        # builds attractor:local (builds base first if absent)
make docker-run          # runs attractor:local, auto-loads .env if present
```

## Architecture

Attractor is a **Kotlin/JVM pipeline runner** for DOT-graph-defined AI workflows. It has two main entry points:

- `src/main/kotlin/attractor/Main.kt` — server entry point, starts `WebMonitorServer`
- `src/main/kotlin/attractor/cli/Main.kt` — CLI entry point, thin REST API client

### Execution pipeline

1. **DOT parsing** (`dot/Lexer.kt`, `dot/Parser.kt`) — converts `.dot` text into `DotGraph`/`DotNode`/`DotEdge` model objects
2. **Transforms** (`transform/`) — applied in order before execution: `VariableExpansionTransform` (expands `$goal` etc.), `StylesheetApplicationTransform` (applies visual styles from `style/Stylesheet.kt`)
3. **Validation** (`lint/Validator.kt`, `lint/BuiltInRules.kt`) — lints the transformed graph before running
4. **Engine** (`engine/Engine.kt`) — walks the graph node-by-node; uses `EdgeSelector` for conditional routing and `RetryPolicy` for back-off; emits `ProjectEvent`s via `ProjectEventBus`
5. **Handlers** (`handlers/`) — each node type maps to a handler via `HandlerRegistry`. Shape-to-type mapping:
   - `Mdiamond` → `start`, `Msquare` → `exit`
   - `box` → `codergen` (LLM prompt node)
   - `diamond` → `conditional` gate
   - `hexagon` → `wait.human` (human review gate)
   - `component` → `parallel` fan-out, `tripleoctagon` → `parallel.fan_in`
   - `parallelogram` → `tool`, `house` → `stack.manager_loop`
6. **LLM adapters** (`llm/adapters/`) — two execution modes: Direct API (`AnthropicAdapter`, `OpenAIAdapter`, `GeminiAdapter`, `CustomApiAdapter`) and CLI subprocess (`AnthropicCliAdapter`, `OpenAICliAdapter`, `GeminiCliAdapter`, `CopilotCliAdapter`)

### Web / API layer

- `web/WebMonitorServer.kt` — HTTP server (JDK `com.sun.net.httpserver`), serves dashboard SPA and SSE event streams at `/events` and `/events/{id}`
- `web/RestApiRouter.kt` — versioned REST API at `/api/v1/` (37 endpoints)
- `web/ProjectRegistry.kt` — in-memory registry of active pipeline runs
- `web/ProjectRunner.kt` — runs the `Engine` for a single pipeline in a background thread
- `web/DotGenerator.kt` — LLM-powered DOT generation from natural language prompts

### Persistence

- `db/RunStore.kt` — interface for pipeline state persistence
- `db/SqliteRunStore.kt` — default SQLite backend (`attractor.db`)
- `db/JdbcRunStore.kt` — shared JDBC impl for MySQL/PostgreSQL
- `db/RunStoreFactory.kt` — selects backend from `ATTRACTOR_DB_*` env vars
- `db/DatabaseConfig.kt` — parses connection strings (supports both full JDBC URLs and simplified `postgres://` / `mysql://` URLs)

### State model

- `state/Context.kt` — per-node execution context (inputs, outputs, node info)
- `state/Outcome.kt` — `success` / `failure` / `cancelled` etc.
- `state/Checkpoint.kt` — serializable snapshot for persist & resume
- `state/ArtifactStore.kt` — stores node output artifacts to the filesystem

### Key conventions

- The `build.gradle.kts` compiles to JVM target 22; Java 25 is required to run (Gradle 9.4.0).
- Tests use JUnit 5 + Kotest assertions + H2 in-memory database for DB tests.
- The `ATTRACTOR_DEBUG` env var enables debug logging and stack traces.
- `bin/attractor` is a shell wrapper that auto-locates the latest CLI JAR.
