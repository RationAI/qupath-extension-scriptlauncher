# ─────────────────────────────────────────────────────────────────────────────
# Stage 0 — Fetch QuPath distribution
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS qupath-dist

ARG QUPATH_VERSION=0.6.0
ARG QUPATH_ARCHIVE=QuPath-v${QUPATH_VERSION}-Linux.tar.xz
ARG QUPATH_DOWNLOAD_URL=https://github.com/qupath/qupath/releases/download/v${QUPATH_VERSION}/${QUPATH_ARCHIVE}
ARG STARDIST_VERSION=0.6.0
ARG STARDIST_JAR=qupath-extension-stardist-${STARDIST_VERSION}.jar
ARG STARDIST_DOWNLOAD_URL=https://github.com/qupath/qupath-extension-stardist/releases/download/v${STARDIST_VERSION}/${STARDIST_JAR}

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates xz-utils \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /tmp/qupath /opt/QuPath/lib/app \
    && curl -fsSL "${QUPATH_DOWNLOAD_URL}" -o /tmp/qupath/${QUPATH_ARCHIVE} \
    && tar -xJf /tmp/qupath/${QUPATH_ARCHIVE} -C /tmp/qupath \
    && find /tmp/qupath -maxdepth 3 -type d -name "QuPath" -print -quit | xargs -I {} cp -a {}/. /opt/QuPath/ \
    && curl -fsSL "${STARDIST_DOWNLOAD_URL}" -o /opt/QuPath/lib/app/${STARDIST_JAR} \
    && rm -rf /tmp/qupath

# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build ScriptAPI and launcher JARs
# Build context: qupath-extension-scriptlauncher directory
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

ARG SCRIPT_API_REPO=https://github.com/Filip-Vrubel-Bachelor-Thesis/ScriptAPI
ARG SCRIPT_API_REF=main
ARG QUPATH_LIB_DIR=/opt/QuPath/lib/app

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Copy launcher source tree
COPY ./  qupath-extension-scriptlauncher/

# Clone ScriptAPI from GitHub
RUN git clone --depth 1 --branch ${SCRIPT_API_REF} ${SCRIPT_API_REPO} ScriptAPI

# Copy QuPath lib JARs needed for compilation (compile-only)
COPY --from=qupath-dist /opt/QuPath/ /opt/QuPath/

# Build ScriptAPI then the launcher (composite build resolves ScriptAPI from source)
RUN cd ScriptAPI && ./gradlew build -q -PqupathLibDir=${QUPATH_LIB_DIR}
RUN cd qupath-extension-scriptlauncher && ./gradlew build -q -PqupathLibDir=${QUPATH_LIB_DIR}


# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre

# ── Copy QuPath installation ─────────────────────────────────────────────────
COPY --from=qupath-dist /opt/QuPath/ /opt/QuPath/
RUN chmod +x /opt/QuPath/bin/QuPath

# ── Install extension JARs into QuPath's app classpath ───────────────────────
# Placed in lib/app/ alongside all other QuPath JARs so they are on the
# classpath when we invoke java directly with "/opt/QuPath/lib/app/*".
COPY --from=builder /build/ScriptAPI/build/libs/script-api-0.1.0.jar \
                    /opt/QuPath/lib/app/
COPY --from=builder /build/qupath-extension-scriptlauncher/build/libs/qupath-extension-scriptlauncher-0.1.0.jar \
                    /opt/QuPath/lib/app/

# ── Install scripts ───────────────────────────────────────────────────────────
COPY scripts/ /scripts/

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


