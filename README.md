# Corey's Attractor

[![CI](https://github.com/coreydaley/coreys-attractor/actions/workflows/ci.yml/badge.svg)](https://github.com/coreydaley/coreys-attractor/actions/workflows/ci.yml) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **Based on the fantastic work from [StrongDM's Software Factory](https://factory.strongdm.ai/) and the [Attractor project](https://github.com/strongdm/attractor).**
>
> StrongDM built a software factory — an automated development system where AI agents write code, run validation, and improve continuously without humans writing or reviewing code. Attractor is the core orchestration engine that makes this possible: a pipeline runner where you define workflows as directed graphs (using [Graphviz DOT](https://graphviz.org/doc/info/lang.html) format) and Attractor executes them, dispatching work to LLMs, waiting on human gates, and routing conditionally between branches.
>
> The name "Attractor" comes from dynamical systems theory — an attractor is a state or pattern that a system naturally converges toward over time. In the context of agentic pipelines, the idea is that well-defined goal-oriented graphs will pull the AI's execution toward a desired outcome, even across retries, branches, and failures. Rather than scripting every step imperatively, you describe the shape of the solution and let the system find its way there.
>
> This repository is a personal implementation of that concept, built as a learning project to explore agentic workflow orchestration.

A DOT-based pipeline runner that orchestrates multi-stage AI workflows. You define pipelines as directed graphs in [Graphviz DOT](https://graphviz.org/doc/info/lang.html) format, and Attractor executes each node by dispatching work to an LLM, waiting for human review, running parallel branches, or following conditional edges — all observable in real time through a built-in web dashboard.

## Features

- **DOT pipelines** — define workflows as `.dot` directed graphs; nodes are tasks, edges are transitions
- **LLM integration** — nodes call Anthropic Claude, OpenAI GPT, or Google Gemini based on configuration
- **Conditional branching** — edges carry `condition=` attributes that route execution based on node outcomes
- **Parallel execution** — fan-out / fan-in nodes run multiple branches concurrently
- **Human gates** — `type="wait.human"` nodes pause execution for interactive approval/rejection
- **Retry & back-off** — configurable per-node retry policy with exponential back-off
- **Persist & resume** — run state is stored in SQLite; crashed runs can be resumed from checkpoints
- **Web dashboard** — real-time SSE-powered UI at `http://localhost:7070`; supports multiple concurrent pipelines; upload `.dot` files via the browser
- **In-app documentation** — built-in `/docs` page with four tabs (Web App, REST API, CLI, DOT Format); accessible from the Docs button in the nav bar; no external dependencies, works offline
- **REST API v1** — 35-endpoint versioned REST API at `/api/v1/`; see [`docs/api/rest-v1.md`](docs/api/rest-v1.md) for the full reference

## Requirements

- **Java 21** (Gradle 8.7 is incompatible with Java 25+)
- Gradle 8.7 (wrapper included, or use the system Gradle)
- GNU Make (included on macOS and most Linux distros)

## Quick Start

```bash
make install-deps   # install Java 21 and git (interactive, detects OS/package manager)
make build          # compile and assemble
make run            # start the web interface on port 7070
```

## Make Targets

| Target | Description |
|--------|-------------|
| `make help` | List all available targets and options |
| `make build` | Compile and assemble the application |
| `make jar` | Build only the fat JAR (`build/libs/coreys-attractor-*.jar`) |
| `make run` | Run via Gradle — picks up source changes without rebuilding the JAR |
| `make run-jar` | Build the fat JAR (if needed) and run it directly (faster startup) |
| `make test` | Run the test suite |
| `make check` | Run tests and all static checks |
| `make clean` | Delete all build output |
| `make dist` | Build distribution archives (`.tar` and `.zip`) |
| `make cli-jar` | Build the CLI fat JAR (`build/libs/coreys-attractor-cli-devel.jar`) |
| `make release` | Build versioned server + CLI JARs for distribution |
| `make install-deps` | Interactively install Java 21 and git using your OS package manager |

### Make Options

Pass these on the command line to override defaults:

| Option | Default | Description |
|--------|---------|-------------|
| `WEB_PORT=<n>` | `7070` | Web UI port |
| `JAVA_HOME=<path>` | `/opt/homebrew/opt/openjdk@21/…` | Path to JDK 21 |

```bash
make run WEB_PORT=8080
make run-jar JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

## Build

```bash
make build
```

Or manually (Java 21 must be active):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew jar
```

The output jar is written to `build/libs/coreys-attractor-*.jar`.

## Run

```bash
make run           # via Gradle (auto-reloads classpath changes)
make run-jar       # via the pre-built fat JAR (faster startup)
```

Or directly:

```
java -jar build/libs/coreys-attractor-1.0.0.jar [options]
```

### Options

| Flag | Description |
|------|-------------|
| `--logs-root <dir>` | Directory for logs and artifacts (default: `logs/<name>-<timestamp>`) |
| `--web-port <n>` | Web interface port (default: `7070`) |

### Environment Variables

| Variable | Description |
|----------|-------------|
| `ANTHROPIC_API_KEY` | API key for Anthropic Claude |
| `OPENAI_API_KEY` | API key for OpenAI GPT |
| `GEMINI_API_KEY` | API key for Google Gemini |
| `ATTRACTOR_DEBUG` | Set to any value to enable debug output and stack traces |

Once running, open `http://localhost:7070` (or your chosen port) in a browser to start creating and executing pipelines. From the web interface you can describe a pipeline goal in natural language, review the generated DOT graph, and run it — all without touching the command line.

Click **Docs** in the navigation bar to open the built-in documentation window. It provides comprehensive, self-contained reference material organized into four tabs — Web App, REST API, CLI, and DOT Format — served directly by the Attractor server with no external dependencies.

## Pipeline Format

Pipelines are written in Graphviz DOT. Attractor interprets node shapes and attributes to decide how each node is executed.

### Node types

| Shape / Attribute | Behavior |
|-------------------|----------|
| `shape=Mdiamond` | Start node |
| `shape=Msquare` | Exit node |
| `shape=box` (default) | LLM prompt node — `prompt=` attribute is sent to the configured model |
| `shape=diamond` | Conditional gate — evaluates outgoing edge `condition=` attributes |
| `shape=hexagon` or `type="wait.human"` | Human review gate — pauses for interactive input |
| Parallel / fan-out nodes | Multiple outgoing edges from a single node run concurrently |

### Edge attributes

| Attribute | Description |
|-----------|-------------|
| `label` | Display label shown in the dashboard |
| `condition` | Boolean expression evaluated against the upstream node's outcome (e.g. `outcome=success`, `outcome!=success`) |

### Graph attributes

| Attribute | Description |
|-----------|-------------|
| `goal` | A natural-language goal string interpolated into prompts via `$goal` |
| `label` | Display name for the pipeline |

### Examples

**Linear pipeline** (`examples/simple.dot`):
```dot
digraph Simple {
    graph [goal="Run tests and report results", label="Simple Pipeline"]
    start    [shape=Mdiamond]
    exit     [shape=Msquare]
    plan     [label="Plan",      prompt="Plan the implementation for: $goal"]
    implement [label="Implement", prompt="Implement the plan for: $goal"]
    test     [label="Test",      prompt="Run tests and verify the implementation"]
    start -> plan -> implement -> test -> exit
}
```

**Conditional retry loop** (`examples/branching.dot`):
```dot
digraph Branch {
    graph [goal="Implement and validate a feature"]
    start     [shape=Mdiamond]
    exit      [shape=Msquare]
    implement [label="Implement", prompt="Implement: $goal"]
    validate  [label="Validate",  prompt="Run tests"]
    gate      [shape=diamond, label="Tests passing?"]
    start -> implement -> validate -> gate
    gate -> exit      [label="Yes", condition="outcome=success"]
    gate -> implement [label="No",  condition="outcome!=success"]
}
```

**Human review gate** (`examples/human-review.dot`):
```dot
digraph Review {
    graph [goal="Review and ship a change"]
    start    [shape=Mdiamond]
    exit     [shape=Msquare]
    generate [shape=box,     prompt="Generate code changes for: $goal"]
    review   [shape=hexagon, label="Review Changes", type="wait.human"]
    fix      [shape=box,     label="Fix Issues", prompt="Fix the issues found in review"]
    start -> generate -> review
    review -> exit [label="[A] Approve"]
    review -> fix  [label="[F] Fix Issues"]
    fix -> review
}
```

## Web API

The server exposes a versioned REST API at `/api/v1/` with 35 endpoints covering pipelines, artifacts, DOT operations, settings, models, and SSE event streams. All request and response bodies use JSON.

Selected endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | Dashboard UI |
| `GET` | `/docs` | Built-in documentation window (four tabs) |
| `GET` | `/api/v1/pipelines` | List all pipeline states |
| `POST` | `/api/v1/pipelines` | Submit a new pipeline |
| `GET` | `/api/v1/pipelines/{id}` | Get a single pipeline (includes `dotSource`) |
| `PATCH` | `/api/v1/pipelines/{id}` | Update pipeline metadata |
| `DELETE` | `/api/v1/pipelines/{id}` | Delete a pipeline |
| `POST` | `/api/v1/pipelines/{id}/rerun` | Re-run a completed/failed pipeline |
| `POST` | `/api/v1/pipelines/{id}/pause` | Pause a running pipeline |
| `POST` | `/api/v1/pipelines/{id}/resume` | Resume a paused pipeline |
| `POST` | `/api/v1/pipelines/{id}/cancel` | Cancel a running pipeline |
| `POST` | `/api/v1/pipelines/{id}/archive` | Archive a pipeline |
| `POST` | `/api/v1/pipelines/{id}/iterations` | Create a new pipeline version (iterate) |
| `GET` | `/api/v1/pipelines/{id}/family` | List all versions in a pipeline family |
| `GET` | `/api/v1/pipelines/{id}/artifacts` | List artifacts for a pipeline |
| `GET` | `/api/v1/pipelines/{id}/artifacts.zip` | Download all artifacts as a ZIP |
| `GET` | `/api/v1/pipelines/{id}/export` | Export pipeline + artifacts as a ZIP |
| `POST` | `/api/v1/pipelines/import` | Import a previously exported pipeline ZIP |
| `POST` | `/api/v1/dot/generate` | Generate a DOT pipeline from a text prompt |
| `POST` | `/api/v1/dot/validate` | Validate a DOT pipeline |
| `GET` | `/api/v1/settings` | List all settings |
| `PUT` | `/api/v1/settings/{key}` | Update a setting |
| `GET` | `/api/v1/models` | List available LLM models |
| `GET` | `/events` | SSE stream of all pipeline state updates |
| `GET` | `/events/{id}` | SSE stream for a single pipeline |

For the complete endpoint listing with request/response shapes and `curl` examples, see [`docs/api/rest-v1.md`](docs/api/rest-v1.md) or open the built-in **Docs** window from the web UI.

## CLI

The `attractor` CLI lets you manage pipelines, artifacts, DOT graphs, settings, and models from the command line.

### Build

```bash
make cli-jar
```

This produces `build/libs/coreys-attractor-cli-devel.jar`. For a versioned release artifact:

```bash
make release
```

### Run

```bash
# Via the built JAR directly
java -jar build/libs/coreys-attractor-cli-devel.jar --help

# Via the bin/ wrapper (auto-locates the latest CLI JAR)
bin/attractor --help
```

Add `bin/` to your `$PATH` to use `attractor` as a bare command anywhere.

### Global Flags

| Flag | Description |
|------|-------------|
| `--host <url>` | Server base URL (default: `http://localhost:7070`) |
| `--output json` | Output raw JSON instead of formatted tables |
| `--help`, `-h` | Show usage information |
| `--version` | Print version and exit |

### Commands

#### Pipeline management

```bash
attractor pipeline list
attractor pipeline get <id>
attractor pipeline create --file pipeline.dot [--name "My Pipeline"] [--simulate] [--no-auto-approve] [--prompt "text"]
attractor pipeline update <id> [--file pipeline.dot] [--prompt "text"]
attractor pipeline delete <id>

# Lifecycle
attractor pipeline rerun <id>
attractor pipeline pause <id>
attractor pipeline resume <id>
attractor pipeline cancel <id>
attractor pipeline archive <id>
attractor pipeline unarchive <id>

# Inspection
attractor pipeline stages <id>
attractor pipeline family <id>

# Polling
attractor pipeline watch <id> [--interval-ms 2000] [--timeout-ms 60000]

# Versioning
attractor pipeline iterate <id> --file updated.dot [--prompt "text"]
```

#### Artifacts

```bash
attractor artifact list <pipeline-id>
attractor artifact get <pipeline-id> <node-id>
attractor artifact download-zip <pipeline-id> [--output out.zip]
attractor artifact stage-log <pipeline-id> <node-id> [--output node.log]
attractor artifact failure-report <pipeline-id> [--output report.json]
attractor artifact export <pipeline-id> [--output export.zip]
attractor artifact import --file export.zip
```

#### DOT graph tools

```bash
attractor dot generate --prompt "Build and test a Go app" [--model <id>] [--output pipeline.dot]
attractor dot generate-stream --prompt "..." [--model <id>]       # streaming output
attractor dot validate --file pipeline.dot
attractor dot render --file pipeline.dot [--output pipeline.png]
attractor dot fix --file pipeline.dot [--error "message"] [--model <id>] [--output fixed.dot]
attractor dot fix-stream --file pipeline.dot [--error "message"] [--model <id>]
attractor dot iterate --file pipeline.dot --prompt "Add a test stage" [--model <id>] [--output next.dot]
attractor dot iterate-stream --file pipeline.dot --prompt "..." [--model <id>]
```

#### Settings

```bash
attractor settings list
attractor settings get <key>
attractor settings set <key> <value>
```

#### Models

```bash
attractor models list
```

#### Events (SSE stream)

```bash
attractor events              # stream all pipeline events
attractor events <pipeline-id> # stream events for one pipeline until terminal state
```

### Examples

```bash
# Create and watch a pipeline
attractor pipeline create --file my.dot --name "My Pipeline"
attractor pipeline watch <id>

# Generate a DOT file from a prompt, then run it
attractor dot generate --prompt "Deploy a container image" --output deploy.dot
attractor pipeline create --file deploy.dot
attractor pipeline watch <id>

# Export all artifacts from a completed pipeline
attractor artifact export <id> --output artifacts.zip

# Use JSON output for scripting
attractor pipeline list --output json | jq '.[0].id'
```

For the full REST API reference, see [`docs/api/rest-v1.md`](docs/api/rest-v1.md).

## Project Structure

```
src/main/kotlin/attractor/
├── Main.kt                  # CLI entrypoint
├── cli/                     # CLI command implementations (Kotlin client for REST API v1)
├── condition/               # Edge condition evaluator
├── db/                      # SQLite persistence (RunStore)
├── dot/                     # DOT parser and graph model
├── engine/                  # Execution loop, retry policy
├── events/                  # Pipeline event types and event bus
├── handlers/                # Node handlers (LLM, human, parallel, conditional, …)
├── human/                   # Human review gate logic
├── lint/                    # Pipeline linting
├── llm/                     # LLM provider clients (Anthropic, OpenAI, Gemini)
├── state/                   # Run state model
├── style/                   # Terminal/output style helpers
├── transform/               # Pipeline graph transformations
└── web/                     # HTTP server, SSE, dashboard, REST API
    ├── WebMonitorServer.kt  # HTTP server, dashboard SPA, /docs endpoint
    ├── RestApiRouter.kt     # Versioned REST API (/api/v1/)
    └── …
examples/                    # Sample .dot pipelines
docs/
├── api/rest-v1.md           # Full REST API reference (35 endpoints)
└── sprints/                 # Sprint planning and history
```

## Running Tests

```bash
make test     # run the test suite
make check    # run tests and all static checks
```

Or directly:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew test
```

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

---

## Disclaimer

> **This entire codebase — including this README — was generated by AI.**
>
> Use it at your own risk. No guarantees are made about correctness, security, stability, or fitness for any purpose. This is a personal learning project and should not be used in production without thorough review and testing.
