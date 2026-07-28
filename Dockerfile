# syntax=docker/dockerfile:1

# ---- build stage: full JDK to run Gradle ----
# Pinned to the *builder's* architecture rather than the target's. Everything this stage emits is JVM
# bytecode and shell scripts — `installDist` produces `lib/*.jar` plus `bin/kodkod` — so one build
# serves every target platform, and BuildKit runs it once instead of once per platform. Without the
# pin, a multi-arch build runs the whole Kotlin compile under QEMU emulation for the non-native
# architecture, which is 5-15x slower on JVM workloads and was most of the wall-clock of a release.
# Only the runtime stage below is genuinely per-architecture, because only the JRE is.
FROM --platform=$BUILDPLATFORM bellsoft/liberica-openjdk-alpine:25 AS build
WORKDIR /app

# Resolve dependencies first so this layer is cached across source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Build the runnable distribution (build/install/kodkod).
COPY src ./src
RUN ./gradlew --no-daemon installDist

# ---- runtime stage: lightweight Liberica JRE on Alpine ----
FROM bellsoft/liberica-openjre-alpine:25 AS runtime

LABEL org.opencontainers.image.title="kodkod" \
      org.opencontainers.image.description="docker-compose companion that restarts unhealthy containers and auto-updates images" \
      org.opencontainers.image.source="https://github.com/Heapy/kodkod" \
      org.opencontainers.image.licenses="Apache-2.0"

# Lets a running kodkod recognise its own container and never act on itself (see SELF_LABEL).
LABEL io.heapy.kodkod.self="true"

WORKDIR /app
COPY --from=build /app/build/install/kodkod/ ./

# Talks to the Docker Engine API over the mounted unix socket; no extra tooling needed.
ENTRYPOINT ["/app/bin/kodkod"]
