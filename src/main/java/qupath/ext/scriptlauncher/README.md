ScriptLauncherExtension — README
=================================

Purpose
-------
`ScriptLauncherExtension` is a small QuPath extension that integrates the Empaia remote WSI client/ImageServer and provides automatic discovery and opening of EMPAIA-hosted WSIs inside QuPath.

What it does
------------
- Reads EMPAIA configuration (API base, job id, optional token) from the environment or extension configuration.
- Calls the EMPAIA `/inputs/my_wsi` endpoint to discover available WSI(s).
- Instantiates `EmpaiaRemoteWsiImageServer` for a selected WSI id so QuPath can display the WSI without native OpenSlide.
- Optionally launches or runs scripts that operate on the opened image.

Configuration
-------------
The extension looks for environment variables (or properties) similar to:
- EMPAIA_APP_API — the base API URL
- EMPAIA_JOB_ID — EMPAIA job id to query
- EMPAIA_TOKEN — bearer token for authenticated requests

Build & install
---------------
Build a JAR for the extension using Gradle from the repository root. Example:

```bash
# build the project
./gradlew clean build -x test

# The produced JAR will be in build/libs/
ls build/libs/
```

Install the extension into QuPath by copying the built JAR into the QuPath extensions folder. For QuPath v0.6 the typical destination is:

```
/path/to/QuPath/v0.6/extensions/
```

After copying the JAR, restart QuPath.

Usage notes
-----------
- Ensure the EMPAIA API settings are correct before starting QuPath so the extension can auto-discover WSIs.
- If the extension cannot find metadata or images, enable debug logging to see the exact URLs and JSON the client receives.

Troubleshooting
---------------
- If tiles fail with 404: verify the WSI id used by the client matches the EMPAIA resource id and that the EMPAIA job exposes tile/region endpoints.
- If metadata parsing fails: check that `/inputs/my_wsi` returns the expected JSON shape (extent.x, extent.y, levels[].extent.x/y, tile_extent.x/y). The client currently assumes this shape.

