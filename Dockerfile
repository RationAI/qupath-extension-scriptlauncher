# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build ScriptAPI and launcher JARs
# Build context: launcher repo root (ScriptAPI is cloned during build)
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

ARG LAUNCHER_SRC=qupath-extension-scriptlauncher
ARG SCRIPT_API_REPO=https://github.com/Filip-Vrubel-Bachelor-Thesis/ScriptAPI
ARG SCRIPT_API_REF=main
ARG QUPATH_LIB_DIR=/build/QuPath-v0.6.0-Linux/QuPath/lib/app

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy launcher source tree
COPY ${LAUNCHER_SRC}/    qupath-extension-scriptlauncher/

# Clone ScriptAPI from GitHub
RUN git clone --depth 1 --branch ${SCRIPT_API_REF} ${SCRIPT_API_REPO} ScriptAPI

# Copy local QuPath lib JARs needed for compilation (compile-only)
COPY QuPath-v0.6.0-Linux/QuPath/lib/app/  QuPath-v0.6.0-Linux/QuPath/lib/app/

# Build ScriptAPI then the launcher (composite build resolves ScriptAPI from source)
RUN cd ScriptAPI && ./gradlew build -q -PqupathLibDir=${QUPATH_LIB_DIR}
RUN cd qupath-extension-scriptlauncher && ./gradlew build -q -PqupathLibDir=${QUPATH_LIB_DIR}


# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# ── Copy local QuPath installation ───────────────────────────────────────────
COPY QuPath-v0.6.0-Linux/QuPath/ /opt/QuPath/
RUN chmod +x /opt/QuPath/bin/QuPath

# ── Install extension JARs into QuPath's app classpath ───────────────────────
# Placed in lib/app/ alongside all other QuPath JARs so they are on the
# classpath when we invoke java directly with "/opt/QuPath/lib/app/*".
COPY --from=builder /build/ScriptAPI/build/libs/script-api-0.1.0.jar \
                    /opt/QuPath/lib/app/
COPY --from=builder /build/qupath-extension-scriptlauncher/build/libs/qupath-extension-scriptlauncher-0.1.0.jar \
                    /opt/QuPath/lib/app/
COPY ["QuPath/v0.6/extensions/catalogs/QuPath catalog/QuPath StarDist extension/v0.6.0/main-jar/qupath-extension-stardist-0.6.0.jar", "/opt/QuPath/lib/app/"]

# ── Install scripts ───────────────────────────────────────────────────────────
COPY qupath-extension-scriptlauncher/scripts/ /scripts/

RUN useradd --system --uid 10001 --create-home appuser \
    && chown -R appuser:appuser /opt/QuPath /scripts
USER appuser

# ── Runtime environment ───────────────────────────────────────────────────────
# Supply at container start via -e or --env-file:
#   EMPAIA_BASE_API  — e.g. https://host/api/app/v3
#   EMPAIA_JOB_ID    — EMPAIA job UUID
#   EMPAIA_TOKEN     — bearer token (optional)
#   QUPATH_SCRIPT    — path to analysis script, e.g. /scripts/example_cell_detection.groovy
ENTRYPOINT ["java", "-cp", "/opt/QuPath/lib/app/*", "qupath.ext.scriptlauncher.EmpaiaScriptManager"]


