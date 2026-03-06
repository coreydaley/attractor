---
title: "Web App"
weight: 10
---

## Getting Started

Attractor is an AI project orchestration system. You define your workflow as a DOT graph — a directed graph where each node is an LLM-powered stage — and Attractor executes it, handling retries, failure diagnosis, and real-time progress monitoring.

**Start the server:**

```bash
# Via Makefile
make run

# Via JAR directly
java -jar attractor-server-*.jar --web-port 7070
```

**Open the UI:** http://localhost:7070

## Navigation

The top navigation bar has five views:

| View | Purpose |
|------|---------|
| **Monitor** | Real-time status of all active projects. Each project gets a tab showing its stage list, live log, and graph. |
| **🚀 Create** | Write or generate a DOT project and submit it for execution. |
| **📁 Archived** | Table of archived completed, failed, or cancelled projects. |
| **📥 Import** | Upload a previously exported project ZIP file. |
| **⚙️ Settings** | Configure execution mode, provider toggles, CLI commands, and UI preferences. |

## Creating a Project

There are three ways to create a project:

### Option A — Generate from natural language

1. Type a description in the natural language input (e.g., *"Build a Go application and run its tests"*)
2. Click **Generate** — the LLM produces a DOT graph
3. Review the graph in the preview pane (toggle between Source and Graph views)
4. Optionally click **Iterate** to refine the project via LLM
5. Click **Create**

### Option B — Write DOT directly

Paste or type a valid DOT graph into the editor in the Create view, then click **Create**.

### Option C — Upload a .dot file

Click **📂 Upload .dot** in the Generated DOT section to open a file picker. Select a `.dot` file from disk — the DOT source loads into the editor, the NL prompt is cleared, and the graph renders automatically. Click **Create** to execute it. The original filename is preserved and used for artifact labelling.

## Project States

| Status | Meaning |
|--------|---------|
| `idle` | Created but not yet started |
| `running` | Actively executing stages |
| `paused` | Execution suspended — awaiting Resume |
| `completed` | All stages finished successfully |
| `failed` | A stage encountered an unrecoverable error |
| `cancelled` | Manually stopped by the user |

## Monitoring a Project

Click a project tab in the Monitor view to open its detail panel:

- **Stage list** — each stage shown with status badge, duration, and a log icon
- **Log panel** — scrollable live log of project events and LLM output
- **Graph panel** — rendered SVG of the DOT graph with stage status colors overlaid

### Action buttons

| Button | When available | Effect |
|--------|----------------|--------|
| Cancel | Running or paused | Immediately terminates execution |
| Pause | Running | Suspends after current stage completes |
| Resume | Paused | Resumes from the paused stage |
| Re-run | Completed or failed | Restarts the project from the beginning |
| Iterate | Completed or failed | Opens the Create view for a new version |
| View Failure Report | Failed | Shows the AI-generated failure diagnosis |
| Export | Any terminal state | Downloads a ZIP containing full project metadata and all artifact files |
| Archive | Completed or failed | Moves to the Archived view |
| Delete | Completed, failed, or cancelled | Permanently removes the project and its artifacts |

## Git History Panel

Every project detail tab includes a collapsible **Git History** bar, located between the project description and the Stages card. It shows the workspace git history for the project — a commit is recorded automatically after each terminal-state event (completed, failed, cancelled, paused).

### Collapsed view

The bar shows at a glance:

```
⎇ main  •  4 commits  •  last: "Run run-001 completed: 3 stages" (2 min ago)
```

Click the bar (or the ▶ chevron) to expand it into a commit log table.

### Expanded view

The expanded panel shows a table of the most recent commits: short hash, subject line, and time-ago. Click the bar again to collapse it.

### Degraded states

| State | Display |
|-------|---------|
| `git` not on PATH | ⎇ Git unavailable — workspace history requires git on PATH |
| Repo not yet initialized | ⎇ Git repo not initialized |
| No commits yet | ⎇ main  •  0 commits  •  no history yet |

### Refresh

- **Auto-refresh** — the panel updates automatically 500ms after the project reaches a terminal state (completed/failed/cancelled/paused)
- **Manual refresh** — click the ↻ button in the bar header to fetch the latest git state on demand

> **Requirement:** The `git` binary must be available on the server's PATH. Check **Settings → System Tools** to confirm it is detected.

## Project Versions (Iterate)

Clicking **Iterate** on a completed or failed project opens the Create view pre-filled with the project's DOT source. When you submit, a new project is created in the same *family* — sharing the same `familyId`. Use the `<<` and `>>` arrows in the project panel header to navigate between family members.

## Export ZIP Contents

Click **Export** on any finished project to download a ZIP archive containing both the project metadata and everything the project produced during its run. You can import this ZIP on another Attractor instance to fully restore the project — no re-run needed.

### Top-level files

| File | Description |
|------|-------------|
| `project-meta.json` | All project fields: ID, DOT source, original prompt, status, options, timestamps, display name, and family ID. Used by Import to reconstruct the project record. |

### artifacts/ directory

The `artifacts/` directory inside the ZIP mirrors the project's on-disk workspace:

| Path | Description |
|------|-------------|
| `artifacts/manifest.json` | Run summary written at the start of execution: run ID, graph name, goal, and start time. |
| `artifacts/checkpoint.json` | Internal resume state written after every stage: completed nodes, context variables, retry counts, stage durations. Used by Re-run and Resume. |
| `artifacts/failure_report.json` | AI-generated failure diagnosis — only present when the project failed and was not recovered. |
| `artifacts/workspace/` | The shared working directory for the entire run. All files the LLM created or modified live here: source code, build output, test results, generated reports, etc. This is where the actual deliverables are. |
| `artifacts/{nodeId}/prompt.md` | The exact prompt sent to the LLM for this stage, with all variable substitutions applied. |
| `artifacts/{nodeId}/response.md` | The complete LLM response text for this stage. |
| `artifacts/{nodeId}/live.log` | Real-time log of every tool call, command execution, and its output for this stage. This is what you see in the stage log viewer in the UI. |
| `artifacts/{nodeId}/status.json` | Stage outcome record: SUCCESS / FAILED / PARTIAL_SUCCESS, notes, error message if any. |
| `artifacts/{nodeId}_repair/` | If a stage failed and Attractor attempted an LLM-guided repair, this directory holds the repair attempt's own prompt, response, log, and status — same layout as a normal stage directory. |

One `{nodeId}/` directory is created per stage. The `workspace/` directory is shared across all stages, so files written in an early stage are visible to later ones.

> **Note:** `artifacts/workspace/` is where you'll find the actual deliverables — code, compiled binaries, test results, or any other files the LLM was instructed to produce. Stage directories (`artifacts/{nodeId}/`) contain the paper trail of how each stage was executed.

### Artifact browser

You can browse individual files directly in the UI without exporting. Click the log icon next to any stage in the stage list to open the artifact browser for that stage, or use the REST API (`GET /api/v1/projects/{id}/artifacts`) to list and fetch individual files programmatically.

## Failure Diagnosis

When a stage fails, Attractor automatically asks the LLM to diagnose the failure and generates a `failure_report.json` in the project's artifact directory. Click **View Failure Report** to see the structured diagnosis. The report is also included in the artifacts ZIP download.

## Import / Export

- **Export** — downloads a ZIP containing `project-meta.json` (all project fields) plus the full `artifacts/` directory (workspace, stage logs, prompts, responses). Use this to back up a project or move it to another Attractor instance.
- **Import** — upload an exported ZIP via the Import view in the nav. The project is restored immediately with its original status and artifacts — no re-run is triggered. If the same project (matched by `familyId`) already exists, the import is skipped by default.

> **Note:** Old export ZIPs that contain only `project-meta.json` without an `artifacts/` directory are still importable — missing fields default gracefully and the project is restored as metadata-only.

## Database Configuration

Attractor stores project run history in a database. By default it uses a local SQLite file (`attractor.db`). Set `ATTRACTOR_DB_*` environment variables at startup to switch to MySQL or PostgreSQL.

The active backend is shown in the startup log: `[attractor] Database: SQLite (attractor.db)`

### Connection string (ATTRACTOR_DB_URL)

Set `ATTRACTOR_DB_URL` to a JDBC URL. Simplified URLs without the `jdbc:` prefix are also accepted:

```bash
# PostgreSQL (JDBC form)
export ATTRACTOR_DB_URL="jdbc:postgresql://localhost:5432/attractor?user=app&password=secret"
# PostgreSQL (simplified)
export ATTRACTOR_DB_URL="postgres://app:secret@localhost:5432/attractor"

# MySQL (JDBC form)
export ATTRACTOR_DB_URL="jdbc:mysql://localhost:3306/attractor?user=app&password=secret"
# MySQL (simplified)
export ATTRACTOR_DB_URL="mysql://app:secret@localhost:3306/attractor"
```

### Individual parameters

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_DB_TYPE` | `sqlite` | Backend: `sqlite`, `mysql`, or `postgresql` (also `postgres`) |
| `ATTRACTOR_DB_HOST` | `localhost` | Database server hostname |
| `ATTRACTOR_DB_PORT` | `3306` / `5432` | Port (default depends on type) |
| `ATTRACTOR_DB_NAME` | `attractor.db` / `attractor` | Database name or SQLite file path |
| `ATTRACTOR_DB_USER` | — | Database username |
| `ATTRACTOR_DB_PASSWORD` | — | Database password (never logged) |
| `ATTRACTOR_DB_PARAMS` | — | Extra JDBC query params, e.g. `sslmode=require` |

> **Security:** Use environment variables for credentials, not command-line arguments. The startup log always shows a credential-free display name. Use `ATTRACTOR_DB_PARAMS=sslmode=require` for encrypted connections in production.

Attractor creates the database schema automatically on first start. A misconfigured `ATTRACTOR_DB_TYPE` causes a clear startup error and clean exit.

## Settings

### Execution Mode

**Direct API** (default) — Attractor makes HTTP calls directly to provider REST APIs. Requires the corresponding API key environment variable to be set before startup.

**CLI subprocess** — Attractor shells out to installed CLI tools. No environment-variable API keys are needed; authentication is handled by the tool itself.

### Providers

| Provider | Modes | Notes |
|----------|-------|-------|
| Anthropic (claude) | API, CLI | API: requires `ATTRACTOR_ANTHROPIC_API_KEY`. CLI: requires the `claude` binary. |
| OpenAI (codex) | API, CLI | API: requires `ATTRACTOR_OPENAI_API_KEY`. CLI: requires the `codex` binary. |
| Google (gemini) | API, CLI | API: requires `ATTRACTOR_GEMINI_API_KEY` or `ATTRACTOR_GOOGLE_API_KEY`. CLI: requires the `gemini` binary. |
| GitHub Copilot | CLI only | Requires `gh copilot` extension. Hidden in Direct API mode. |
| Custom (OpenAI-compatible) | API only | Any endpoint implementing `/v1/chat/completions` — Ollama, LM Studio, vLLM, etc. Configure host, port, optional API key, and model name. The badge shows endpoint reachability. |

> **Custom provider tip:** For Ollama, set host to `http://localhost`, port to `11434`, leave API key blank, and set model to the name of a pulled model (e.g. `llama3.2`). The endpoint must be running before Attractor attempts to reach it.

### Direct API — Advanced Options

These environment variables override the default API endpoints. Useful for proxies, private deployments, or Azure-compatible endpoints.

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_ANTHROPIC_BASE_URL` | `https://api.anthropic.com` | Override Anthropic API base URL |
| `ATTRACTOR_OPENAI_BASE_URL` | `https://api.openai.com` | Override OpenAI API base URL |
| `ATTRACTOR_OPENAI_ORG_ID` | — | OpenAI organization ID (sent as `OpenAI-Organization` header) |
| `ATTRACTOR_OPENAI_PROJECT_ID` | — | OpenAI project ID (sent as `OpenAI-Project` header) |
| `ATTRACTOR_GEMINI_BASE_URL` | `https://generativelanguage.googleapis.com` | Override Gemini API base URL |

### Custom Provider Bootstrap

These environment variables seed the Custom provider's Settings UI on first start. Values saved through the Settings UI take precedence on subsequent starts.

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_CUSTOM_API_ENABLED` | `false` | Set to `true` to enable the custom provider on startup |
| `ATTRACTOR_CUSTOM_API_HOST` | `http://localhost` | Base URL of the OpenAI-compatible endpoint |
| `ATTRACTOR_CUSTOM_API_PORT` | `11434` | Port number (leave blank to omit from URL) |
| `ATTRACTOR_CUSTOM_API_KEY` | — | API key (optional — Ollama does not require one) |
| `ATTRACTOR_CUSTOM_API_MODEL` | `llama3.2` | Model name to use for requests |

### CLI command templates

In CLI mode, each provider has an editable command template. Use `{prompt}` as the substitution placeholder for the generated prompt text.

### System Tools

The System Tools grid shows detected binaries on the host. **Required** tools (`java`, `git`, `graphviz`) must be present for core features to work — a warning banner appears at the top of every page if any are missing. **Optional** tools are used by LLM-generated project stages and do not block Attractor itself.

Install all required runtime tools with: `make install-runtime-deps`

## Debug Mode

Set `ATTRACTOR_DEBUG` to any non-empty value to enable verbose logging and full stack traces in error output.

```bash
ATTRACTOR_DEBUG=1 make run
```
