---
title: "Docker"
weight: 50
---

## Overview

Attractor uses two Docker images published to the GitHub Container Registry:

- **`ghcr.io/coreydaley/attractor-base`** — A base image built on Ubuntu 24.04 LTS (Noble) containing the Java 25 JRE and all system tools (`graphviz`, `git`, `python3`, `ruby`, `nodejs`, `golang-go`, `rustc`, and more). It is only rebuilt when `docker/Dockerfile.base` changes between releases, keeping release builds fast.
- **`ghcr.io/coreydaley/attractor`** — The server image. Built on every release by copying the pre-built JAR on top of `attractor-base`. On a typical release where only the code changes, this step completes in ~2 minutes instead of ~8.

The container runs as a non-root user (`attractor`). All persistent state — the SQLite database and pipeline output — is written to mounted volumes.

## Quick Start

The easiest way to run Attractor is with Docker Compose using the `docker/compose.yml` file in the repository:

```bash
docker compose -f docker/compose.yml up -d
# or:
make docker-up
```

Open http://localhost:7070 once the container is running.

## Image Tags

Each release publishes the following tags for the server image (`ghcr.io/coreydaley/attractor`):

| Tag | Example | Notes |
|-----|---------|-------|
| `latest` | `latest` | Most recent release |
| `<major>.<minor>.<patch>` | `1.2.3` | Exact release — recommended for production |
| `<major>.<minor>` | `1.2` | Latest patch in this minor series |
| `<major>` | `1` | Latest release in this major series |

The base image (`ghcr.io/coreydaley/attractor-base`) is tagged the same way, but only on releases where `docker/Dockerfile.base` has changed. The server image always uses `attractor-base:latest`.

## Docker Compose

`docker/compose.yml` is the recommended way to run Attractor. It supports optional profiles for Ollama and PostgreSQL, and mounts named volumes for all persistent data.

### Default (SQLite)

```bash
docker compose -f docker/compose.yml up -d
# or:
make docker-up
```

### With Ollama (local LLM)

```bash
docker compose -f docker/compose.yml --profile ollama up -d
# or:
make docker-up PROFILES=ollama
```

Configure Attractor to reach the Ollama container by adding these to your `.env`:

```bash
ATTRACTOR_CUSTOM_API_ENABLED=true
ATTRACTOR_CUSTOM_API_HOST=http://ollama
ATTRACTOR_CUSTOM_API_PORT=11434
ATTRACTOR_CUSTOM_API_MODEL=llama3.2
```

Pull a model once both containers are running:

```bash
docker compose -f docker/compose.yml exec ollama ollama pull llama3.2
```

### With PostgreSQL

```bash
docker compose -f docker/compose.yml --profile postgres up -d
# or:
make docker-up PROFILES=postgres
```

Add these to your `.env` to point Attractor at the Compose-managed database:

```bash
ATTRACTOR_DB_TYPE=postgresql
ATTRACTOR_DB_HOST=postgres
ATTRACTOR_DB_PORT=5432
ATTRACTOR_DB_NAME=attractor
ATTRACTOR_DB_USER=attractor
ATTRACTOR_DB_PASSWORD=attractor
```

### Combining profiles

```bash
docker compose -f docker/compose.yml --profile ollama --profile postgres up -d
# or:
make docker-up PROFILES="ollama postgres"
```

### Stopping

```bash
docker compose -f docker/compose.yml down
# or:
make docker-down
```

Named volumes (`attractor-data`, `attractor-projects`, `ollama-data`, `postgres-data`) are preserved when you stop. Pass `--volumes` to remove them too.

## CLI Subprocess Mode

CLI subprocess mode is **not supported** when running Attractor inside Docker. The AI CLI tools (`claude`, `codex`, `gemini`, `gh copilot`) are not installed in the container, and their authentication state (OAuth tokens stored in `~/.claude/`, `~/.config/gh/`, etc.) is not available inside the container.

**Direct API mode is the recommended approach for Docker.** Pass your API keys as environment variables at runtime — see [Passing API Keys](#passing-api-keys) below.

If you specifically need CLI subprocess mode, there are two options:

### Option 1: Mount host authentication directories

If the CLI tool is installed and authenticated on the host, mount its config directory into the container. The container runs as the `attractor` user, so paths resolve relative to `/home/attractor/`.

```bash
# Claude CLI — mount host auth state read-only
docker run --rm -p 7070:7070 \
  -v ~/.claude:/home/attractor/.claude:ro \
  -v "$(pwd)/data:/app/data" \
  -v "$(pwd)/projects:/app/projects" \
  ghcr.io/coreydaley/attractor:latest
```

The CLI binary itself still needs to be available inside the container. The `attractor-base` image does not include any AI CLI tools.

### Option 2: Build a custom image

Extend `attractor-base` with the CLI tools you need:

```dockerfile
FROM ghcr.io/coreydaley/attractor-base:latest

# Install Claude CLI (authenticates via ANTHROPIC_API_KEY — no browser login needed)
RUN npm install -g @anthropic-ai/claude-code

# Copy your server JAR
COPY build/libs/attractor-server-*.jar /app/attractor-server.jar
# ... rest of your Dockerfile
```

The Claude CLI is the easiest to use in a container because it authenticates via the `ATTRACTOR_ANTHROPIC_API_KEY` environment variable without requiring a browser login. Other CLIs (`codex`, `gemini`, `gh copilot`) require interactive OAuth flows that are difficult to complete inside a container.

---

## Passing API Keys

API keys are passed as environment variables at run time — they are never baked into the image.

```bash
cp .env.example .env
# edit .env and fill in your keys
make docker-up        # Compose picks up .env automatically
```

Docker Compose loads `.env` from the project root automatically. When running `docker run` directly, pass keys via `--env-file .env` or individual `-e KEY=value` flags.

## Environment Variables

### LLM Provider API Keys

| Variable | Description |
|----------|-------------|
| `ATTRACTOR_ANTHROPIC_API_KEY` | API key for Anthropic Claude (Direct API mode) |
| `ATTRACTOR_OPENAI_API_KEY` | API key for OpenAI GPT (Direct API mode) |
| `ATTRACTOR_GEMINI_API_KEY` | API key for Google Gemini (Direct API mode) |
| `ATTRACTOR_GOOGLE_API_KEY` | Alternative to `ATTRACTOR_GEMINI_API_KEY` |

### Custom OpenAI-Compatible API

These env vars bootstrap the custom provider settings on first start. Values saved through the Settings UI take precedence on subsequent starts.

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_CUSTOM_API_ENABLED` | `false` | Set to `true` to enable the custom provider |
| `ATTRACTOR_CUSTOM_API_HOST` | `http://localhost` | Base URL of the OpenAI-compatible endpoint |
| `ATTRACTOR_CUSTOM_API_PORT` | `11434` | Port number (leave blank to omit from URL) |
| `ATTRACTOR_CUSTOM_API_KEY` | — | API key (optional — Ollama does not require one) |
| `ATTRACTOR_CUSTOM_API_MODEL` | `llama3.2` | Model name to use for requests |

### Storage

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_DB_TYPE` | `sqlite` | Database backend: `sqlite`, `mysql`, or `postgresql` |
| `ATTRACTOR_DB_NAME` | `/app/data/attractor.db` | Database name or SQLite file path |
| `ATTRACTOR_DB_HOST` | — | Database server hostname (MySQL/PostgreSQL) |
| `ATTRACTOR_DB_PORT` | — | Database port (defaults by type) |
| `ATTRACTOR_DB_USER` | — | Database username |
| `ATTRACTOR_DB_PASSWORD` | — | Database password |
| `ATTRACTOR_DB_URL` | — | Full JDBC URL — overrides all individual `ATTRACTOR_DB_*` vars |
| `ATTRACTOR_PROJECTS_DIR` | `/app/projects` | Directory where pipeline output is written (logs, workspaces, artifacts) |

### Debug

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_DEBUG` | — | Set to any non-empty value to enable debug logging and stack traces |

## Volumes

| Container path | Compose volume | Description |
|----------------|----------------|-------------|
| `/app/data` | `attractor-data` | SQLite database and persistent app data |
| `/app/projects` | `attractor-projects` | Pipeline output: stage logs, workspaces, checkpoints, and artifacts |

Both paths must be mounted to preserve state across container restarts and upgrades.

## Ports

| Port | Description |
|------|-------------|
| `7070` | Web UI and REST API. Map with `-p <host-port>:7070`. Override the host port with `ATTRACTOR_PORT` in your `.env`. |

## Building Locally

```bash
make docker-build-base   # build the base image (attractor-base:local)
make docker-build        # build the server image (attractor:local); builds base if not present
make docker-run          # run attractor:local, auto-loads .env if present
```

`make docker-build` checks for `attractor-base:local` and only rebuilds it when missing, so it is safe to run repeatedly.

Or directly with Docker:

```bash
# Build base first (only needed once, or when docker/Dockerfile.base changes)
docker build -f docker/Dockerfile.base -t attractor-base:local .

# Build server image
docker build -f docker/Dockerfile -t attractor:local .

# Run with both volumes mounted
docker run --rm -p 7070:7070 \
  -v "$(pwd)/data:/app/data" \
  -v "$(pwd)/projects:/app/projects" \
  attractor:local
```
