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
# These bootstrap the settings on first start; values saved via the UI take precedence.
ENV ATTRACTOR_CUSTOM_API_ENABLED=""
ENV ATTRACTOR_CUSTOM_API_HOST=""
ENV ATTRACTOR_CUSTOM_API_PORT=""
ENV ATTRACTOR_CUSTOM_API_KEY=""
ENV ATTRACTOR_CUSTOM_API_MODEL=""

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "/app/attractor-server.jar"]
CMD ["--web-port", "7070"]
