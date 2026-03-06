FROM ghcr.io/coreydaley/attractor-base:latest

RUN groupadd --system attractor && useradd --system --gid attractor --no-create-home attractor

WORKDIR /app

COPY build/libs/attractor-server-*.jar /app/attractor-server.jar

# Create data directories and set ownership before declaring volumes.
# Docker discards RUN changes to volume paths made after VOLUME is declared.
RUN mkdir -p /app/data /app/projects && chown -R attractor:attractor /app
USER attractor

# Persist the SQLite database and project output outside the container
VOLUME /app/data
VOLUME /app/projects
ENV ATTRACTOR_DB_NAME=/app/data/attractor.db
ENV ATTRACTOR_PROJECTS_DIR=/app/projects

# LLM provider API keys — supply at runtime via --env-file or -e flags
ENV ATTRACTOR_ANTHROPIC_API_KEY=""
ENV ATTRACTOR_OPENAI_API_KEY=""
ENV ATTRACTOR_GEMINI_API_KEY=""
ENV ATTRACTOR_GOOGLE_API_KEY=""

# Custom OpenAI-compatible API (Ollama, LM Studio, vLLM, etc.)
# These bootstrap the settings on first start; values saved via the UI take precedence.
ENV ATTRACTOR_CUSTOM_API_ENABLED=""
ENV ATTRACTOR_CUSTOM_API_HOST=""
ENV ATTRACTOR_CUSTOM_API_PORT=""
ENV ATTRACTOR_CUSTOM_API_KEY=""
ENV ATTRACTOR_CUSTOM_API_MODEL=""

ARG ATTRACTOR_IMAGE_VERSION=dev
ENV ATTRACTOR_IMAGE_VERSION=$ATTRACTOR_IMAGE_VERSION

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "/app/attractor-server.jar"]
CMD ["--web-port", "7070"]
