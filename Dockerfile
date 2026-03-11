# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build ScriptAPI and launcher JARs
# Build context: /home/filip   (run: docker build -f qupath-extension-scriptlauncher/Dockerfile .)
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy source trees
COPY ScriptAPI/                        ScriptAPI/
COPY qupath-extension-scriptlauncher/  qupath-extension-scriptlauncher/

# Copy local QuPath lib JARs needed for compilation (compile-only)
COPY QuPath-v0.6.0-Linux/QuPath/lib/app/  QuPath-v0.6.0-Linux/QuPath/lib/app/

# The flatDir paths in build.gradle.kts reference /home/filip/QuPath-v0.6.0-Linux/...
# so we symlink to match
RUN mkdir -p /home/filip \
    && ln -s /build/QuPath-v0.6.0-Linux /home/filip/QuPath-v0.6.0-Linux

# Build ScriptAPI then the launcher (composite build resolves ScriptAPI from source)
RUN cd ScriptAPI && ./gradlew build -q
RUN cd qupath-extension-scriptlauncher && ./gradlew build -q


# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM ubuntu:22.04

RUN apt-get update && apt-get install -y --no-install-recommends \
        libfreetype6 \
        libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

# ── Copy local QuPath installation ───────────────────────────────────────────
COPY QuPath-v0.6.0-Linux/QuPath/ /opt/QuPath/
RUN chmod +x /opt/QuPath/bin/QuPath

# ── Install extension JARs into QuPath's app classpath ───────────────────────
# QuPath uses a jpackage-generated launcher that reads an explicit classpath
# from lib/app/QuPath.cfg — dropping JARs into lib/app/ alone is not enough.
COPY --from=builder /build/ScriptAPI/build/libs/script-api-0.1.0.jar \
                    /opt/QuPath/lib/app/
COPY --from=builder /build/qupath-extension-scriptlauncher/build/libs/qupath-extension-scriptlauncher-0.1.0.jar \
                    /opt/QuPath/lib/app/
RUN sed -i '/^\[JavaOptions\]/i app.classpath=$APPDIR/script-api-0.1.0.jar\napp.classpath=$APPDIR/qupath-extension-scriptlauncher-0.1.0.jar' \
        /opt/QuPath/lib/app/QuPath.cfg

# ── Install scripts ───────────────────────────────────────────────────────────
COPY qupath-extension-scriptlauncher/scripts/ /scripts/

# ── Runtime environment ───────────────────────────────────────────────────────
# Supply at container start via -e or --env-file:
#   EMPAIA_BASE_API  — e.g. https://host/api/app/v3
#   EMPAIA_JOB_ID    — EMPAIA job UUID
#   EMPAIA_TOKEN     — bearer token (optional)
#   QUPATH_SCRIPT    — path to analysis script, e.g. /scripts/example_cell_detection.groovy
ENV PATH="/opt/QuPath/bin:${PATH}"

ENTRYPOINT ["QuPath", "script", "/scripts/launcher.groovy"]


