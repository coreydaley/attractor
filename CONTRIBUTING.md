# Contributing to Attractor

## Philosophy

This project follows the [Software Factory](https://factory.strongdm.ai/) philosophy pioneered by StrongDM: **no humans write, read, or verify the actual code in this repository.** All code is authored, reviewed, and refined exclusively by AI agents.

This is a deliberate choice, not a shortcut. The core principle is that human involvement in the code itself introduces the very biases, shortcuts, and blind spots that agents are free from. Humans set direction, define goals, describe desired behaviour, and evaluate outcomes — but the code is the agent's domain entirely.

If you're considering contributing, please read [factory.strongdm.ai](https://factory.strongdm.ai/) first.

---

Thanks for your interest in contributing! This is a personal learning project, but contributions, bug reports, and suggestions are welcome.

## Getting Started

### Prerequisites

- Java 25
- Git
- Graphviz (`dot`) — required for graph rendering

```bash
make install-runtime-deps   # installs Java 25, git, and graphviz (interactive)
make install-dev-deps       # also installs entr for dev-mode file watching
```

### Local Setup

```bash
git clone https://github.com/coreydaley/attractor.git
cd attractor
make build
make test
```

### Running Locally

```bash
make run          # start the web UI at http://localhost:7070
make run WEB_PORT=8080   # use a different port
```

Set at least one LLM API key before running pipelines:

```bash
export ATTRACTOR_ANTHROPIC_API_KEY=...
export ATTRACTOR_OPENAI_API_KEY=...
export ATTRACTOR_GEMINI_API_KEY=...
```

### Running with Docker

```bash
cp .env.example .env   # fill in your API keys
make docker-up         # starts the server via Docker Compose
```

### Building Docker Images Locally

Attractor uses two images: a **base image** (`attractor-base`) containing the JRE and system tools, and a **server image** (`attractor`) that layers the application JAR on top. The base image must exist before the server image can be built.

```bash
# 1. Build the base image (only needed once, or when docker/Dockerfile.base changes)
make docker-build-base

# 2. Build the server image (requires the base image to exist)
make docker-build

# 3. Run the locally built image
make docker-run
```

`make docker-build` checks for `attractor-base:local` automatically and rebuilds it if missing, so it is also safe to just run `make docker-build` directly.

**When to rebuild the base image:**

The base image only needs to be rebuilt when `docker/Dockerfile.base` changes — for example, when system packages or the JRE version are updated. For normal code changes, only the server image needs to be rebuilt.

**Rebuilding from scratch:**

```bash
docker rmi attractor-base:local attractor:local
make docker-build-base
make docker-build
```

---

## How to Contribute

### Reporting Bugs

Open an issue using the **Bug report** template. Include:
- Steps to reproduce
- Expected vs actual behaviour
- Java version, OS, and how you're running the app
- The `.dot` source and any relevant log output (`ATTRACTOR_DEBUG=1` for verbose logs)

### Suggesting Features

Open an issue using the **Feature request** template. Describe the problem you're trying to solve, not just the solution.

### Submitting a Pull Request

1. Fork the repository and create a branch from `main`:
   ```bash
   git checkout -b your-feature-name
   ```
2. Make your changes. Keep each PR focused — one thing per PR.
3. Run the full check suite before pushing:
   ```bash
   make check
   ```
4. Open a pull request against `main`. Fill out the PR template.

---

## Code Style

- Kotlin conventions follow the [official Kotlin coding style guide](https://kotlinlang.org/docs/coding-conventions.html).
- Keep functions small and focused.
- Prefer immutability (`val` over `var`).
- No secrets, API keys, or `.env` files in commits — ever.

---

## Testing

```bash
make test     # run the test suite
make check    # run tests + static checks
```

Add tests for any new behaviour. Existing tests must pass.

---

## Commit Messages

Use short, imperative-mood commit messages:

```
fix: handle null outcome in condition evaluator
feat: add retry backoff to ManagerLoopHandler
docs: update pipeline format table in README
```

Prefixes: `feat`, `fix`, `docs`, `test`, `chore`, `refactor`, `ci`, `style`, `perf`.

---

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
