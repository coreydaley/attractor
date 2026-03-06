# docker/

Docker configuration for Attractor.

| File | Description |
|------|-------------|
| `Dockerfile` | Server image — copies the pre-built JAR onto `attractor-base` |
| `Dockerfile.base` | Base image — JRE + system tools (including the Docker CLI); only rebuilt when this file changes |
| `compose.yml` | Docker Compose setup with optional Ollama, PostgreSQL, and Docker-out-of-Docker profiles |
| `compose.docker.yml` | Compose override that mounts the host Docker socket (merged by `PROFILES=docker`) |

## Quick start

```bash
# Start with Docker Compose (recommended)
docker compose -f docker/compose.yml up -d
# or:
make docker-up

# With optional profiles
make docker-up PROFILES=ollama
make docker-up PROFILES="ollama postgres"
make docker-up PROFILES=docker           # mount host Docker socket (Docker-out-of-Docker)
make docker-up PROFILES="docker ollama"

# Stop
make docker-down
```

Copy `.env.example` to `.env` and fill in your API keys before starting.

---

Full documentation: https://attractor.coreydaley.dev/docker/
