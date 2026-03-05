# ── Build stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Download dependencies in a separate layer for better caching
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew dependencies --no-daemon -q

COPY src/ src/

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew releaseJar --no-daemon -q

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
        graphviz \
        git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/build/libs/attractor-server-*.jar /app/attractor-server.jar

# Persist the SQLite database outside the container
VOLUME /app/data
ENV ATTRACTOR_DB_NAME=/app/data/attractor.db

EXPOSE 7070

ENTRYPOINT ["java", "-jar", "/app/attractor-server.jar"]
CMD ["--web-port", "7070"]
