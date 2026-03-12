# QuPath Extension Script Launcher

Headless EMPAIA job runner built on QuPath v0.6.0. Fetches a WSI from EMPAIA, executes a user-authored Groovy analysis script, and posts results back — all from a Docker container with no GUI.

---

## Architecture

```
EmpaiaScriptManager          (platform layer — EMPAIA HTTP, env config, progress polling)
       │
       ▼
EmpaiaScriptApi              (implements ScriptApi — Groovy execution + EMPAIA HTTP outputs)
       │
       ▼
User Groovy script           (depends on ScriptApi only — platform-agnostic)
```

**ScriptAPI** (`qupath.ext:script-api`) is a separate thin JAR containing only the `ScriptApi` interface. User scripts import nothing from this extension — they only depend on `ScriptApi`, making them portable to other platforms.

### Key classes

| Class | Role |
|---|---|
| `EmpaiaScriptManager` | Docker entrypoint. Reads env vars, fetches script name and WSI metadata from EMPAIA, starts execution, polls and forwards progress, finalizes or fails the job. |
| `EmpaiaScriptApi` | Implements `ScriptApi`. Runs the Groovy script on a background thread (QuPath `ImageData`, hierarchy, ROI injection). All EMPAIA HTTP output calls (`postValues`, `postAnnotations`, etc.). |
| `EmpaiaRemoteWsiClient` | Minimal EMPAIA HTTP client — fetches WSI metadata and tiles. |
| `EmpaiaRemoteWsiImageServer` | QuPath `ImageServer` backed by EMPAIA tile API. |
| `ScriptLauncherExtension` | QuPath GUI extension for interactive development and testing. |

---

## Running in Docker

### Build

```bash
# Build context must be the parent directory (contains both repos and QuPath installation)
cd /home/filip
docker build -f qupath-extension-scriptlauncher/Dockerfile -t empaia-qupath:dev .
```

### Run

```bash
docker run --rm \
  -e EMPAIA_BASE_API=https://host/api/app/v3 \
  -e EMPAIA_JOB_ID=<job-uuid> \
  -e EMPAIA_TOKEN=<bearer-token> \
  empaia-qupath:dev
```

The script to execute is resolved automatically: `EmpaiaScriptManager` fetches `/{job_id}/inputs/script` from EMPAIA and looks for a matching `.groovy` file in the scripts directory.

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `EMPAIA_BASE_API` | ✅ | — | EMPAIA App API base URL, e.g. `https://host/api/app/v3` |
| `EMPAIA_JOB_ID` | ✅ | — | EMPAIA job UUID |
| `EMPAIA_TOKEN` | — | — | Bearer token for authenticated access |
| `EMPAIA_POLL_INTERVAL` | — | `2000` | Progress poll interval in milliseconds |
| `QUPATH_SCRIPTS_DIR` | — | `/scripts` | Directory containing Groovy analysis scripts |

---

## Writing Analysis Scripts

Scripts are plain Groovy files placed in the `scripts/` directory (copied to `/scripts/` in the container). Three variables are injected at runtime — **do not import or instantiate them**:

| Variable | Type | Description |
|---|---|---|
| `api` | `ScriptApi` | Post results and communicate with the platform |
| `imageData` | `ImageData` | The opened EMPAIA WSI |
| `hierarchy` | `PathObjectHierarchy` | QuPath object hierarchy |

### Minimal example

```groovy
def roi = api.getInputRoi()          // input ROI from EMPAIA (or null = full slide)
if (roi != null) {
    hierarchy.addObject(roi)
    hierarchy.getSelectionModel().setSelectedObject(roi)
}
api.reportProgress(0.1)

// ... run analysis ...

api.postAnnotations("output_annotations", hierarchy.getDetectionObjects())
api.postValues("output_values", [hierarchy.getDetectionObjects().size()])
api.reportProgress(1.0)
```

### ScriptApi reference

```java
PathObject getInputRoi()                                          // input ROI or null
void postValues(String key, Collection<? extends Number> values)  // numeric outputs
void postAnnotations(String key, Collection<PathObject> objects)  // polygon outputs
void reportProgress(double fraction)                              // 0.0 → 1.0
void fail(String message)                                         // fail the job
```

### Bundled scripts

| Script | Description |
|---|---|
| `example_cell_detection.groovy` | QuPath watershed cell detection within the input ROI |
| `stardist.groovy` | StarDist2D nucleus detection |

---

## Project layout

```
qupath-extension-scriptlauncher/
├── src/main/java/qupath/ext/scriptlauncher/
│   ├── EmpaiaScriptManager.java       # Docker entrypoint
│   ├── EmpaiaScriptApi.java           # ScriptApi implementation + Groovy runner
│   └── ScriptLauncherExtension.java   # QuPath GUI extension
├── src/main/java/qupath/lib/images/servers/remote/
│   ├── EmpaiaRemoteWsiClient.java     # EMPAIA HTTP client
│   └── EmpaiaRemoteWsiImageServer.java
├── scripts/
│   ├── example_cell_detection.groovy
│   └── stardist.groovy
└── Dockerfile                         # Multi-stage build (builder + eclipse-temurin:21-jre)

../ScriptAPI/                          # Separate repo — ScriptApi interface JAR only
```

---

## Requirements

- Java 21
- QuPath v0.6.0 installation at `../QuPath-v0.6.0-Linux/` (for compilation and Docker runtime)
- Docker (for containerized execution)
