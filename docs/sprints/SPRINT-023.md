# Sprint 023: Split Docker Build into Base and Server Images

## Overview

Tagged releases currently rebuild the entire Docker runtime layer on every push — installing
~15 system tools (graphviz, git, Python, Ruby, Node, Go, Rust, GCC, etc.) even though those
tools rarely change. The server JAR changes every release. The right fix is to give each layer
its own lifecycle.

This sprint introduces `Dockerfile.base`, which builds `ghcr.io/coreydaley/attractor-base`
containing the JRE and all OS tool installations. It is only rebuilt and pushed when
`Dockerfile.base` itself has changed since the previous git tag. The main `Dockerfile` is
simplified to a single-stage image: it uses `attractor-base:latest` as its base and copies the
JAR already built by the Gradle step — no second Gradle build inside Docker. The `release.yml`
workflow is restructured into two jobs (`build-base` → `build-and-release`) with an explicit
dependency, keeping everything tag-triggered as before.

The result: release Docker builds go from ~8 minutes to ~3 minutes on typical tags where
`Dockerfile.base` is unchanged.

## Use Cases

1. **Fast release cycle**: Tag `v0.5.0` pushed. `Dockerfile.base` unchanged since `v0.4.1`.
   Base job detects no change, skips the 5-minute apt install. Server image builds in ~2 minutes.

2. **Tooling update**: A new OS tool is added to `Dockerfile.base` and `v0.6.0` is tagged.
   Base job detects the change, rebuilds and pushes `attractor-base`, then server image builds
   on top of the updated base.

3. **First-ever tag**: No previous `v*` tag exists to diff against. Base job safely falls back
   to rebuilding — always rebuild when in doubt.

4. **Multi-platform**: Both `attractor-base` and the server image are `linux/amd64` + `linux/arm64`,
   supporting Intel/AMD servers and Apple Silicon Macs.

5. **No double Gradle build**: The JAR built for the GitHub Release is the same JAR copied into
   the Docker image — no redundant compilation inside the container.

## Architecture

```
Tag push (v*)
  │
  ├─► Job: build-base  (always runs)
  │     │
  │     ├─ Determine PREV_TAG (previous v* tag by version sort)
  │     ├─ git diff PREV_TAG..HEAD -- Dockerfile.base
  │     │    ├─ changed OR no prev tag  → setup-buildx → login → metadata → build+push
  │     │    └─ unchanged              → skip Docker steps (output: rebuild=false)
  │     └─ outputs: rebuild=true|false
  │
  └─► Job: build-and-release  (needs: build-base)
        │
        ├─ Gradle: releaseJar + releaseCliJar
        ├─ Attest server JAR + CLI JAR
        ├─ Create GitHub Release (upload JARs)
        ├─ setup-buildx + login to GHCR
        └─ Build + push attractor (server image, always)
             FROM ghcr.io/coreydaley/attractor-base:latest
             COPY build/libs/attractor-server-*.jar /app/attractor-server.jar
             [ENV, VOLUME, EXPOSE, ENTRYPOINT, CMD]

Image registry:
  ghcr.io/coreydaley/attractor-base     ← OS + tools (rebuilt when Dockerfile.base changes)
    tags (when rebuilt): latest, 0.5.0, 0.5, 0
  ghcr.io/coreydaley/attractor          ← server image (every release)
    tags: latest, 0.5.0, 0.5, 0

Dockerfile.base  →  attractor-base  ←  Dockerfile (runtime FROM)
                                            ↑
                               build/libs/*.jar (from Gradle step)
```

## Implementation Plan

### Phase 1: Create `Dockerfile.base` (~20%)

**Files:**
- `Dockerfile.base` — Create

**Tasks:**
- [ ] Create `Dockerfile.base`:
  ```dockerfile
  FROM eclipse-temurin:21-jre

  RUN apt-get update && apt-get install -y --no-install-recommends \
          # required tools
          graphviz \
          git \
          # optional tools
          python3 \
          ruby \
          nodejs \
          npm \
          golang-go \
          rustc \
          cargo \
          gcc \
          g++ \
          clang \
          make \
          gradle \
          maven \
          curl \
      && rm -rf /var/lib/apt/lists/*
  ```
- [ ] Verify it builds locally: `docker build -f Dockerfile.base .`

---

### Phase 2: Simplify `Dockerfile` to single-stage image (~20%)

**Files:**
- `Dockerfile` — Modify
- `.dockerignore` — Create or modify

**Tasks:**
- [ ] Replace the current two-stage `Dockerfile` with a single-stage image:
  ```dockerfile
  FROM ghcr.io/coreydaley/attractor-base:latest

  WORKDIR /app

  COPY build/libs/attractor-server-*.jar /app/attractor-server.jar

  # Persist the SQLite database outside the container
  VOLUME /app/data
  ENV ATTRACTOR_DB_NAME=/app/data/attractor.db

  # LLM provider API keys — supply at runtime via --env-file or -e flags
  ENV ANTHROPIC_API_KEY=""
  ENV OPENAI_API_KEY=""
  ENV GEMINI_API_KEY=""
  ENV GOOGLE_API_KEY=""

  # Custom OpenAI-compatible API (Ollama, LM Studio, vLLM, etc.)
  ENV ATTRACTOR_CUSTOM_API_ENABLED=""
  ENV ATTRACTOR_CUSTOM_API_HOST=""
  ENV ATTRACTOR_CUSTOM_API_PORT=""
  ENV ATTRACTOR_CUSTOM_API_KEY=""
  ENV ATTRACTOR_CUSTOM_API_MODEL=""

  EXPOSE 7070

  ENTRYPOINT ["java", "-jar", "/app/attractor-server.jar"]
  CMD ["--web-port", "7070"]
  ```
- [ ] Create/update `.dockerignore` to allow only the server JAR from `build/`:
  ```
  # Exclude everything by default
  build/
  # Allow the release JAR
  !build/libs/attractor-server-*.jar
  ```
- [ ] Verify server image builds locally (requires `attractor-base` pulled and JAR built):
  ```bash
  docker build -f Dockerfile .
  ```

---

### Phase 3: Restructure `release.yml` into two jobs (~50%)

**Files:**
- `.github/workflows/release.yml` — Modify

**Tasks:**

#### Job: `build-base` (new)

- [ ] Add job `build-base` at the top of `jobs:`:
  ```yaml
  build-base:
    name: Build Base Image
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    outputs:
      rebuilt: ${{ steps.detect.outputs.rebuild }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Detect if Dockerfile.base changed
        id: detect
        run: |
          CURRENT_TAG="${GITHUB_REF_NAME}"
          PREV_TAG=$(git tag --sort=-version:refname | grep '^v' | grep -v "^${CURRENT_TAG}$" | head -1)
          if [ -z "$PREV_TAG" ]; then
            echo "No previous v* tag found — rebuilding base image"
            echo "rebuild=true" >> $GITHUB_OUTPUT
          elif git diff --name-only "$PREV_TAG" HEAD -- Dockerfile.base | grep -q .; then
            echo "Dockerfile.base changed since $PREV_TAG — rebuilding base image"
            echo "rebuild=true" >> $GITHUB_OUTPUT
          else
            echo "Dockerfile.base unchanged since $PREV_TAG — skipping base image rebuild"
            echo "rebuild=false" >> $GITHUB_OUTPUT
          fi

      - name: Set up Docker Buildx
        if: steps.detect.outputs.rebuild == 'true'
        uses: docker/setup-buildx-action@v3

      - name: Log in to GitHub Container Registry
        if: steps.detect.outputs.rebuild == 'true'
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Extract base image metadata
        if: steps.detect.outputs.rebuild == 'true'
        id: meta
        uses: docker/metadata-action@v5
        with:
          images: ghcr.io/${{ github.repository_owner }}/attractor-base
          tags: |
            type=semver,pattern={{version}}
            type=semver,pattern={{major}}.{{minor}}
            type=semver,pattern={{major}}
            type=raw,value=latest

      - name: Build and push base image
        if: steps.detect.outputs.rebuild == 'true'
        uses: docker/build-push-action@v6
        with:
          context: .
          file: Dockerfile.base
          push: true
          platforms: linux/amd64,linux/arm64
          tags: ${{ steps.meta.outputs.tags }}
          labels: ${{ steps.meta.outputs.labels }}
          cache-from: type=gha,scope=attractor-base
          cache-to: type=gha,mode=max,scope=attractor-base
  ```

#### Job: `build-and-release` (existing, restructured)

- [ ] Add `needs: build-base` to existing job
- [ ] Keep all existing steps (Gradle, attest, GitHub Release) unchanged
- [ ] Update the server image Docker steps:
  - Add `setup-buildx-action` step (move up, before the Docker steps)
  - Update metadata action: image remains `ghcr.io/${{ github.repository }}`; add `type=raw,value=latest`
  - Update `build-push-action`: add `file: Dockerfile`, scope cache to `attractor-server`
  - Remove the `--mount=type=cache` build args (those were for the Dockerfile builder stage, which is now gone)
- [ ] The Gradle `releaseJar` step must run before the Docker build step (it already does — JAR is in `build/libs/`)

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `Dockerfile.base` | Create | JRE + all OS tools; builds `attractor-base` |
| `Dockerfile` | Modify | Single-stage: FROM attractor-base + COPY pre-built JAR |
| `.dockerignore` | Create/Modify | Allow `build/libs/attractor-server-*.jar` into build context |
| `.github/workflows/release.yml` | Modify | Add `build-base` job; `build-and-release` needs it |

## Definition of Done

### Dockerfiles
- [ ] `Dockerfile.base` exists with all OS tool installations
- [ ] `Dockerfile` is single-stage: `FROM attractor-base:latest`, `COPY` JAR only
- [ ] No Gradle/JDK build stage in `Dockerfile` (JAR supplied from build context)
- [ ] `.dockerignore` allows `build/libs/attractor-server-*.jar` through
- [ ] `docker build -f Dockerfile.base .` succeeds locally
- [ ] `docker build -f Dockerfile .` succeeds locally (with base pulled and JAR built)

### Workflow
- [ ] `release.yml` has `build-base` and `build-and-release` jobs
- [ ] `build-and-release` declares `needs: build-base`
- [ ] `build-base` job always runs; only Docker steps are conditional
- [ ] Previous tag resolved via `git tag --sort=-version:refname | grep '^v'`
- [ ] Base image skips push when `Dockerfile.base` unchanged since previous tag
- [ ] Base image pushes when changed or no previous tag exists
- [ ] `attractor-base` tagged: semver + `latest` (only when rebuilt)
- [ ] `attractor` (server) tagged: semver + `latest` (every release)
- [ ] Both images: `linux/amd64,linux/arm64`
- [ ] GHA caches scoped separately: `attractor-base` and `attractor-server`

### Quality
- [ ] No existing release steps broken (JAR build, attest, GitHub Release unchanged)
- [ ] No secrets added (uses `GITHUB_TOKEN`)
- [ ] YAML valid (no syntax errors)
- [ ] No Gradle dependencies added

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Previous-tag detection wrong (non-v* tag selected) | Low | Medium | Explicit `grep '^v'` filter; version-sort ensures semver ordering |
| No previous tag on first release | Medium | Low | Empty `PREV_TAG` → `rebuild=true` (safe default) |
| `attractor-base:latest` unavailable when server builds | Low | High | `build-and-release` depends on `build-base` job; if base skipped, existing `latest` on GHCR is used |
| Build context includes JAR but JAR missing (no Gradle step yet) | Low | High | Docker build step in `build-and-release` runs after `releaseJar` step — ordering preserved |
| `gradle` apt package is outdated for some projects | Low | Medium | Known; projects needing specific Gradle version should use Gradle wrapper |
| `GITHUB_TOKEN` packages:write scope covers `attractor-base` (different package name) | Low | Medium | `packages: write` covers all packages owned by `github.repository_owner`; confirmed by GH docs |

## Security Considerations

- All packages installed via `apt-get` from official Debian/Ubuntu repos — no curl-pipe installers
- `GITHUB_TOKEN` used for GHCR auth; no additional secrets needed
- `packages: write` permission scoped to `build-base` job only (`build-and-release` also needs it for server image)
- No API keys baked into either image; all env vars default to empty

## Dependencies

- Sprint 022 completed — no in-progress structural work
- Current `Dockerfile` already installs the full tool set (moving it to `Dockerfile.base`)
- Existing `docker/build-push-action`, `docker/metadata-action`, `docker/login-action` (already in use)
- No new Gradle dependencies

## Open Questions

1. **Base tag sparseness**: `attractor-base` will only have semver tags on releases where
   `Dockerfile.base` changed (e.g., `0.5.0`, `0.6.0` — no `0.5.1`). The server `Dockerfile`
   references `:latest` so this is fine functionally. For human traceability, the tag history
   on `attractor-base` clearly shows which releases involved tooling changes.

2. **Local `make build-base` target**: A `make build-base` convenience target for local
   development is useful but not blocking for this sprint. Defer to a follow-up.
