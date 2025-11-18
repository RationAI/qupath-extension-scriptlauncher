Empaia Remote WSI Client and ImageServer
========================================

Purpose
-------
This package contains a minimal EMPAIA HTTP client and a QuPath ImageServer implementation that together allow QuPath to open and display whole-slide images (WSIs) served by an EMPAIA-compatible service — without requiring OpenSlide native libraries.

Key classes
-----------
- EmpaiaRemoteWsiClient
  - Responsible for HTTP requests to the EMPAIA app API (uses java.net.http + Jackson).
  - Main methods:
    - fetchMetadata(): read metadata (extent, levels, tile_extent) for a WSI as returned from `/inputs/my_wsi`.
    - fetchTile(...): download a single tile image from `/tiles/...`.
    - fetchRegion(...): download an arbitrary region from `/regions/...`.

- EmpaiaRemoteWsiImageServer (in this package)
  - Implements QuPath’s ImageServer abstraction, mapping EMPAIA metadata into QuPath ImageServerMetadata and implementing readTile.
  - Strategy (typical): request a region covering the requested tile, falling back to tile endpoints if needed.

Configuration
-------------
The client expects the EMPAIA API base and job to be provided by the extension (ScriptLauncherExtension). Typical environment/configuration keys used by the extension are:
- EMPAIA_APP_API
- EMPAIA_JOB_ID
- EMPAIA_TOKEN 

Build & install (quick)
-----------------------
From the repository root:

```bash
# build jar
./gradlew clean build -x test

# copy the produced jar to QuPath extensions (example path)
cp build/libs/*.jar /path/to/QuPath/v0.6/extensions/
```

After copying the JAR, restart QuPath.

Notes & troubleshooting
----------------------
- This client assumes `inputs/my_wsi` returns the WSI metadata object(s) (extent.x/y, levels[*].extent.x/y, tile_extent.x/y).
- If you see HTTP 404 for tiles, inspect the logged request URLs and ensure the WSI id and level coordinates match the EMPAIA API expectations.
- Increase logging (SLF4J) to debug network/JSON issues.

