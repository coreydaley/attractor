---
title: "Docker"
weight: 50
---

## Overview

Attractor uses two Docker images published to the GitHub Container Registry:

- **`ghcr.io/coreydaley/attractor-base`** — A base image built on Ubuntu 24.04 LTS (Noble) containing the Java 25 JRE and all system tools (`graphviz`, `git`, `python3`, `ruby`, `nodejs`, `golang-go`, `rustc`, and more). It is only rebuilt when `Dockerfile.base` changes between releases, keeping release builds fast.
- **`ghcr.io/coreydaley/attractor`** — The server image. Built on every release by copying the pre-built JAR on top of `attractor-base`. On a typical release where only the code changes, this step completes in ~2 minutes instead of ~8.

SQLite data is stored in a mounted volume so it persists across container restarts.

## Quick Start

```bash
# Pull the latest release
docker pull ghcr.io/coreydaley/attractor:latest

# Run with a local data volume (SQLite persisted to ./data/attractor.db)
docker run --rm -p 7070:7070 -v "$(pwd)/data:/app/data" ghcr.io/coreydaley/attractor:latest
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

The base image (`ghcr.io/coreydaley/attractor-base`) is tagged the same way, but only on releases where `Dockerfile.base` has changed. The server image always uses `attractor-base:latest`.

## Building Locally

```bash
make docker-build-base   # build the base image (attractor-base:local)
make docker-build        # build the server image (attractor:local); builds base if not present
make docker-run          # run attractor:local, auto-loads .env if present
```

`make docker-build` checks for `attractor-base:local` and only rebuilds it when it is missing, so a plain `make docker-build` is safe to run repeatedly.

Or directly with Docker:

```bash
# Build base first (only needed once, or when Dockerfile.base changes)
docker build -f Dockerfile.base -t attractor-base:local -t ghcr.io/coreydaley/attractor-base:latest .

# Build server image
docker build -f Dockerfile -t attractor:local .

docker run --rm -p 7070:7070 -v "$(pwd)/data:/app/data" attractor:local
```

## Passing API Keys

LLM provider API keys are passed as environment variables at run time — they are never baked into the image.

### Using a .env file (recommended)

```bash
cp .env.example .env
# edit .env and fill in your keys
make docker-run       # automatically passes --env-file .env when .env exists
```

Or with Docker directly:

```bash
docker run --rm -p 7070:7070 \
  -v "$(pwd)/data:/app/data" \
  --env-file .env \
  ghcr.io/coreydaley/attractor:latest
```

### Inline -e flags

```bash
docker run --rm -p 7070:7070 \
  -v "$(pwd)/data:/app/data" \
  -e ATTRACTOR_ANTHROPIC_API_KEY=sk-ant-... \
  ghcr.io/coreydaley/attractor:latest
```

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

> **Ollama tip:** If running Ollama as a separate container on the same Docker network, set `ATTRACTOR_CUSTOM_API_HOST` to the Ollama service name (e.g. `http://ollama`) rather than `localhost`.

### Database

By default the container uses SQLite at `/app/data/attractor.db` (set via `ATTRACTOR_DB_NAME`). To use MySQL or PostgreSQL instead, set the following variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `ATTRACTOR_DB_TYPE` | `sqlite` | `sqlite`, `mysql`, or `postgresql` |
| `ATTRACTOR_DB_HOST` | `localhost` | Database server hostname |
| `ATTRACTOR_DB_PORT` | — | Database port (defaults by type) |
| `ATTRACTOR_DB_NAME` | `attractor` | Database name (or SQLite file path) |
| `ATTRACTOR_DB_USER` | — | Database username |
| `ATTRACTOR_DB_PASSWORD` | — | Database password |
| `ATTRACTOR_DB_URL` | — | Full JDBC URL — overrides all individual `ATTRACTOR_DB_*` vars |

## Volumes

| Path | Description |
|------|-------------|
| `/app/data` | SQLite database and any other persistent data. Mount this to preserve state across restarts. |

## Ports

| Port | Description |
|------|-------------|
| `7070` | Web UI and REST API. Map with `-p <host-port>:7070`. |

## Docker Compose Example

```yaml
services:
  attractor:
    image: ghcr.io/coreydaley/attractor:latest
    ports:
      - "7070:7070"
    volumes:
      - attractor-data:/app/data
    environment:
      ATTRACTOR_ANTHROPIC_API_KEY: ${ATTRACTOR_ANTHROPIC_API_KEY}
      ATTRACTOR_OPENAI_API_KEY: ${ATTRACTOR_OPENAI_API_KEY}

volumes:
  attractor-data:
```

With Ollama on the same network:

```yaml
services:
  attractor:
    image: ghcr.io/coreydaley/attractor:latest
    ports:
      - "7070:7070"
    volumes:
      - attractor-data:/app/data
    environment:
      ATTRACTOR_CUSTOM_API_ENABLED: "true"
      ATTRACTOR_CUSTOM_API_HOST: "http://ollama"
      ATTRACTOR_CUSTOM_API_PORT: "11434"
      ATTRACTOR_CUSTOM_API_MODEL: "llama3.2"
    depends_on:
      - ollama

  ollama:
    image: ollama/ollama:latest
    volumes:
      - ollama-data:/root/.ollama

volumes:
  attractor-data:
  ollama-data:
```
