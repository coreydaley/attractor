# =============================================================================
# attractor — Makefile
# =============================================================================
# Gradle 8.7's bundled Kotlin DSL compiler does not support Java 25+.
# JAVA_HOME is pinned to Java 21 (Homebrew). Override on the command line
# if your Java 21 lives elsewhere:
#   make build JAVA_HOME=/path/to/jdk21
# =============================================================================

SHELL     := /bin/bash

JAVA_HOME ?= /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export JAVA_HOME

GRADLEW   := ./gradlew
JAR        = build/libs/attractor-server-devel.jar
WEB_PORT  ?= 7070

.PHONY: help build test clean run run-jar dev jar cli-jar release dist check install-dev-deps install-runtime-deps openapi docker-build-base docker-build docker-run

# Default target — show available targets
help:
	@echo ""
	@echo "  attractor"
	@echo ""
	@echo "  make build          Compile and assemble the application"
	@echo "  make test           Run the test suite"
	@echo "  make clean          Delete all build output"
	@echo "  make run            Run via Gradle (auto-reloads classpath)"
	@echo "  make run-jar        Run the pre-built fat JAR directly"
	@echo "  make dev            Dev mode: watch src/, rebuild + restart on change (requires entr)"
	@echo "  make jar            Build server devel JAR  (attractor-server-devel.jar)"
	@echo "  make cli-jar        Build CLI devel JAR     (attractor-cli-devel.jar)"
	@echo "  make release        Build versioned server + CLI JARs (git tag or SHA[-dirty])"
	@echo "  make dist           Build distribution archives (tar + zip)"
	@echo "  make check          Run tests and static checks"
	@echo "  make openapi        Generate OpenAPI 3.0 specs (JSON + YAML)"
	@echo "  make docker-build-base  Build the base image locally (attractor-base:local)"
	@echo "  make docker-build       Build the server image (attractor:local); builds base if needed"
	@echo "  make docker-run         Run the local Docker image (uses .env if present)"
	@echo "  make install-dev-deps       Install dev dependencies (Java 21, git, entr)"
	@echo "  make install-runtime-deps   Install runtime dependencies (Java 21, git, graphviz)"
	@echo ""
	@echo "  Options (pass on command line):"
	@echo "    WEB_PORT=<n>   Web UI port  (default: $(WEB_PORT))"
	@echo "    JAVA_HOME=<p>  JDK 21 path  (default: $(JAVA_HOME))"
	@echo ""

build:
	$(GRADLEW) build

test:
	$(GRADLEW) test

clean:
	$(GRADLEW) clean

# Run via Gradle's application plugin — picks up source changes without
# needing to rebuild the fat JAR first.
run:
	$(GRADLEW) run --args="--web-port $(WEB_PORT)"

# Run the pre-built fat JAR directly (faster startup, no Gradle overhead).
run-jar: jar
	@test -n "$(JAR)" || (echo "ERROR: no JAR found in build/libs/" && exit 1)
	$(JAVA_HOME)/bin/java -jar $(JAR) --web-port $(WEB_PORT)

# Dev mode: watch src/**/*.kt and rebuild + restart the server on any change.
# Requires entr — install with: brew install entr
dev:
	@WEB_PORT=$(WEB_PORT) JAVA_HOME=$(JAVA_HOME) ./scripts/dev.sh

jar:
	$(GRADLEW) jar

cli-jar:
	$(GRADLEW) cliJar

release:
	$(GRADLEW) releaseJar releaseCliJar
	@echo ""
	@echo "  Release artifacts:"
	@ls build/libs/attractor-server-*.jar build/libs/attractor-cli-*.jar 2>/dev/null \
	  | grep -v -- '-devel' | sed 's/^/    /' || true
	@echo ""

docker-build-base:
	docker build -f Dockerfile.base -t attractor-base:local -t ghcr.io/coreydaley/attractor-base:latest .

docker-build:
	@docker image inspect attractor-base:local > /dev/null 2>&1 || $(MAKE) docker-build-base
	docker build -f Dockerfile -t attractor:local .

# Run the local image. If a .env file exists in this directory it is loaded
# automatically; copy .env.example to .env and fill in your API keys.
docker-run:
	docker run --rm -p $(WEB_PORT):7070 \
	  -v "$(CURDIR)/data:/app/data" \
	  $(if $(wildcard .env),--env-file .env) \
	  attractor:local

dist:
	$(GRADLEW) distTar distZip

check:
	$(GRADLEW) check

# Detect the OS, ask which package manager to use, show the install command,
# and offer to run it. Dependencies: Java 21 JDK, git, graphviz.
install-runtime-deps:
	@set -e; \
	OS=$$(uname -s 2>/dev/null || echo "Unknown"); \
	echo ""; \
	echo "  Detected OS: $$OS"; \
	echo "  Dependencies: Java 21 JDK, git, graphviz"; \
	echo ""; \
	case "$$OS" in \
	  Darwin) \
	    echo "  Select your package manager:"; \
	    echo "    1) Homebrew   (brew)"; \
	    echo "    2) MacPorts   (port)"; \
	    echo ""; \
	    read -rp "  Enter number [1-2]: " choice; \
	    case "$$choice" in \
	      1) CMD="brew install openjdk@21 git graphviz" ;; \
	      2) CMD="sudo port install openjdk21 git graphviz" ;; \
	      *) echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	  Linux) \
	    echo "  Select your package manager:"; \
	    echo "    1) apt      (Debian / Ubuntu / Mint)"; \
	    echo "    2) dnf      (Fedora / RHEL 8+)"; \
	    echo "    3) yum      (CentOS / RHEL 7)"; \
	    echo "    4) pacman   (Arch / Manjaro)"; \
	    echo "    5) apk      (Alpine)"; \
	    echo "    6) zypper   (openSUSE)"; \
	    echo "    7) brew     (Linuxbrew)"; \
	    echo ""; \
	    read -rp "  Enter number [1-7]: " choice; \
	    case "$$choice" in \
	      1) CMD="sudo apt-get install -y openjdk-21-jdk git graphviz" ;; \
	      2) CMD="sudo dnf install -y java-21-openjdk-devel git graphviz" ;; \
	      3) CMD="sudo yum install -y java-21-openjdk-devel git graphviz" ;; \
	      4) CMD="sudo pacman -S --noconfirm jdk21-openjdk git graphviz" ;; \
	      5) CMD="sudo apk add --no-cache openjdk21 git graphviz" ;; \
	      6) CMD="sudo zypper install -y java-21-openjdk-devel git graphviz" ;; \
	      7) CMD="brew install openjdk@21 git graphviz" ;; \
	      *) echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	  MINGW*|CYGWIN*|MSYS*) \
	    echo "  Select your package manager:"; \
	    echo "    1) winget   (Windows Package Manager)"; \
	    echo "    2) choco    (Chocolatey)"; \
	    echo "    3) scoop    (Scoop)"; \
	    echo ""; \
	    read -rp "  Enter number [1-3]: " choice; \
	    case "$$choice" in \
	      1) CMD="winget install Microsoft.OpenJDK.21 Git.Git Graphviz.Graphviz" ;; \
	      2) CMD="choco install openjdk21 git graphviz" ;; \
	      3) CMD="scoop install openjdk21 git graphviz" ;; \
	      *) echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	  *) \
	    echo "  OS not recognised. Select your package manager:"; \
	    echo "    1)  brew     (Homebrew — macOS / Linux)"; \
	    echo "    2)  apt      (Debian / Ubuntu / Mint)"; \
	    echo "    3)  dnf      (Fedora / RHEL 8+)"; \
	    echo "    4)  yum      (CentOS / RHEL 7)"; \
	    echo "    5)  pacman   (Arch / Manjaro)"; \
	    echo "    6)  apk      (Alpine)"; \
	    echo "    7)  zypper   (openSUSE)"; \
	    echo "    8)  port     (MacPorts)"; \
	    echo "    9)  winget   (Windows)"; \
	    echo "   10)  choco    (Chocolatey)"; \
	    echo "   11)  scoop    (Scoop)"; \
	    echo ""; \
	    read -rp "  Enter number [1-11]: " choice; \
	    case "$$choice" in \
	      1)  CMD="brew install openjdk@21 git graphviz" ;; \
	      2)  CMD="sudo apt-get install -y openjdk-21-jdk git graphviz" ;; \
	      3)  CMD="sudo dnf install -y java-21-openjdk-devel git graphviz" ;; \
	      4)  CMD="sudo yum install -y java-21-openjdk-devel git graphviz" ;; \
	      5)  CMD="sudo pacman -S --noconfirm jdk21-openjdk git graphviz" ;; \
	      6)  CMD="sudo apk add --no-cache openjdk21 git graphviz" ;; \
	      7)  CMD="sudo zypper install -y java-21-openjdk-devel git graphviz" ;; \
	      8)  CMD="sudo port install openjdk21 git graphviz" ;; \
	      9)  CMD="winget install Microsoft.OpenJDK.21 Git.Git Graphviz.Graphviz" ;; \
	      10) CMD="choco install openjdk21 git graphviz" ;; \
	      11) CMD="scoop install openjdk21 git graphviz" ;; \
	      *)  echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	esac; \
	echo ""; \
	echo "  Install command:"; \
	echo ""; \
	echo "    $$CMD"; \
	echo ""; \
	read -rp "  Run it now? [y/N] " confirm; \
	echo ""; \
	case "$$confirm" in \
	  [yY]|[yY][eE][sS]) \
	    eval "$$CMD" \
	    ;; \
	  *) \
	    echo "  Copy and run the command above when you're ready." \
	    ;; \
	esac; \
	echo ""

# Generate OpenAPI 3.0 spec files into src/main/resources/api/.
# Re-run whenever the API changes, then rebuild to embed updated specs in the JAR.
openapi:
	python3 scripts/generate-openapi.py

# Detect the OS, ask which package manager to use, show the install command,
# and offer to run it. Dependencies: Java 21 JDK, git, entr.
#
# Written as a single backslash-joined recipe line so it runs in one bash
# invocation — compatible with GNU Make 3.81 (which pre-dates .ONESHELL).
install-dev-deps:
	@set -e; \
	OS=$$(uname -s 2>/dev/null || echo "Unknown"); \
	echo ""; \
	echo "  Detected OS: $$OS"; \
	echo "  Dependencies: Java 21 JDK, git, entr"; \
	echo ""; \
	case "$$OS" in \
	  Darwin) \
	    echo "  Select your package manager:"; \
	    echo "    1) Homebrew   (brew)"; \
	    echo "    2) MacPorts   (port)"; \
	    echo ""; \
	    read -rp "  Enter number [1-2]: " choice; \
	    case "$$choice" in \
	      1) CMD="brew install openjdk@21 git entr" ;; \
	      2) CMD="sudo port install openjdk21 git entr" ;; \
	      *) echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	  Linux) \
	    echo "  Select your package manager:"; \
	    echo "    1) apt      (Debian / Ubuntu / Mint)"; \
	    echo "    2) dnf      (Fedora / RHEL 8+)"; \
	    echo "    3) yum      (CentOS / RHEL 7)"; \
	    echo "    4) pacman   (Arch / Manjaro)"; \
	    echo "    5) apk      (Alpine)"; \
	    echo "    6) zypper   (openSUSE)"; \
	    echo "    7) brew     (Linuxbrew)"; \
	    echo ""; \
	    read -rp "  Enter number [1-7]: " choice; \
	    case "$$choice" in \
	      1) CMD="sudo apt-get install -y openjdk-21-jdk git entr" ;; \
	      2) CMD="sudo dnf install -y java-21-openjdk-devel git entr" ;; \
	      3) CMD="sudo yum install -y java-21-openjdk-devel git entr" ;; \
	      4) CMD="sudo pacman -S --noconfirm jdk21-openjdk git entr" ;; \
	      5) CMD="sudo apk add --no-cache openjdk21 git entr" ;; \
	      6) CMD="sudo zypper install -y java-21-openjdk-devel git entr" ;; \
	      7) CMD="brew install openjdk@21 git entr" ;; \
	      *) echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	  MINGW*|CYGWIN*|MSYS*) \
	    echo "  Note: 'entr' (required for make dev) is not available on Windows."; \
	    echo ""; \
	    echo "  Select your package manager:"; \
	    echo "    1) winget   (Windows Package Manager)"; \
	    echo "    2) choco    (Chocolatey)"; \
	    echo "    3) scoop    (Scoop)"; \
	    echo ""; \
	    read -rp "  Enter number [1-3]: " choice; \
	    case "$$choice" in \
	      1) CMD="winget install Microsoft.OpenJDK.21 Git.Git" ;; \
	      2) CMD="choco install openjdk21 git" ;; \
	      3) CMD="scoop install openjdk21 git" ;; \
	      *) echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	  *) \
	    echo "  OS not recognised. Select your package manager:"; \
	    echo "    1)  brew     (Homebrew — macOS / Linux)"; \
	    echo "    2)  apt      (Debian / Ubuntu / Mint)"; \
	    echo "    3)  dnf      (Fedora / RHEL 8+)"; \
	    echo "    4)  yum      (CentOS / RHEL 7)"; \
	    echo "    5)  pacman   (Arch / Manjaro)"; \
	    echo "    6)  apk      (Alpine)"; \
	    echo "    7)  zypper   (openSUSE)"; \
	    echo "    8)  port     (MacPorts)"; \
	    echo "    9)  winget   (Windows — entr not available)"; \
	    echo "   10)  choco    (Chocolatey — entr not available)"; \
	    echo "   11)  scoop    (Scoop — entr not available)"; \
	    echo ""; \
	    read -rp "  Enter number [1-11]: " choice; \
	    case "$$choice" in \
	      1)  CMD="brew install openjdk@21 git entr" ;; \
	      2)  CMD="sudo apt-get install -y openjdk-21-jdk git entr" ;; \
	      3)  CMD="sudo dnf install -y java-21-openjdk-devel git entr" ;; \
	      4)  CMD="sudo yum install -y java-21-openjdk-devel git entr" ;; \
	      5)  CMD="sudo pacman -S --noconfirm jdk21-openjdk git entr" ;; \
	      6)  CMD="sudo apk add --no-cache openjdk21 git entr" ;; \
	      7)  CMD="sudo zypper install -y java-21-openjdk-devel git entr" ;; \
	      8)  CMD="sudo port install openjdk21 git entr" ;; \
	      9)  CMD="winget install Microsoft.OpenJDK.21 Git.Git" ;; \
	      10) CMD="choco install openjdk21 git" ;; \
	      11) CMD="scoop install openjdk21 git" ;; \
	      *)  echo "  Invalid choice." ; exit 1 ;; \
	    esac \
	    ;; \
	esac; \
	echo ""; \
	echo "  Install command:"; \
	echo ""; \
	echo "    $$CMD"; \
	echo ""; \
	read -rp "  Run it now? [y/N] " confirm; \
	echo ""; \
	case "$$confirm" in \
	  [yY]|[yY][eE][sS]) \
	    eval "$$CMD" \
	    ;; \
	  *) \
	    echo "  Copy and run the command above when you're ready." \
	    ;; \
	esac; \
	echo ""
