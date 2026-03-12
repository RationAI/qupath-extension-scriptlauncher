package qupath.ext.scriptlauncher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiClient;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiImageServer;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * EmpaiaScriptManager — EMPAIA platform manager and Docker entry point.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Read EMPAIA configuration from environment variables</li>
 *   <li>Fetch WSI metadata from EMPAIA and open the image server</li>
 *   <li>Create {@link EmpaiaScriptApi} (EMPAIA implementation of ScriptApi)</li>
 *   <li>Hand control to {@link GroovyScriptRunner} and wait in a polling loop</li>
 *   <li>Forward progress reported by the script to EMPAIA's progress endpoint</li>
 *   <li>Finalize or fail the job when the runner signals completion</li>
 * </ul>
 *
 * <p>This class knows about EMPAIA. It does NOT know about QuPath internals
 * (ImageData, hierarchy, ROIs) — those are the runner's responsibility.
 *
 * <p>Invoked as:
 * <pre>
 *   java -cp "/opt/QuPath/lib/app/*" qupath.ext.scriptlauncher.EmpaiaScriptManager
 * </pre>
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code EMPAIA_BASE_API} — EMPAIA App API base URL (e.g. https://host/api/app/v3)</li>
 *   <li>{@code EMPAIA_JOB_ID}   — EMPAIA job UUID</li>
 *   <li>{@code QUPATH_SCRIPT}   — absolute path to the user Groovy script</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code EMPAIA_TOKEN}         — bearer token for authenticated access</li>
 *   <li>{@code EMPAIA_POLL_INTERVAL} — progress poll interval in ms (default: 2000)</li>
 * </ul>
 */
public class EmpaiaScriptManager {

    private static final Logger logger = LoggerFactory.getLogger(EmpaiaScriptManager.class);
    private static final long DEFAULT_POLL_INTERVAL_MS = 2_000;

    public static void main(String[] args) {
        // ── 1. Read configuration from environment ────────────────────────────
        String baseApi    = System.getenv("EMPAIA_BASE_API");
        String jobId      = System.getenv("EMPAIA_JOB_ID");
        String token      = System.getenv("EMPAIA_TOKEN");
        String scriptPath = System.getenv("QUPATH_SCRIPT");
        long   pollMs     = parseLongEnv("EMPAIA_POLL_INTERVAL", DEFAULT_POLL_INTERVAL_MS);

        if (baseApi == null || jobId == null) {
            logger.error("EMPAIA_BASE_API and EMPAIA_JOB_ID must be set");
            System.exit(1);
        }
        if (scriptPath == null) {
            logger.error("QUPATH_SCRIPT must be set");
            System.exit(1);
        }
        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            logger.error("Script file not found: {}", scriptPath);
            System.exit(1);
        }

        HttpClient httpClient = HttpClient.newHttpClient();

        // ── 2. Fetch WSI metadata from EMPAIA ────────────────────────────────
        Map<String, String> headers = token != null
                ? Map.of("Authorization", "Bearer " + token)
                : Map.of();

        EmpaiaRemoteWsiClient empaiaClient = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);
        EmpaiaRemoteWsiClient.Metadata md;
        try {
            md = empaiaClient.fetchMetadata();
        } catch (Exception e) {
            logger.error("Failed to fetch WSI metadata from EMPAIA", e);
            System.exit(1);
            return;
        }
        if (md == null || md.width <= 0 || md.height <= 0 || md.id == null) {
            logger.error("Invalid WSI metadata from EMPAIA");
            System.exit(1);
            return;
        }
        logger.info("Fetched WSI metadata: id={} size={}x{}", md.id, md.width, md.height);

        // ── 3. Open image server and create ScriptApi ─────────────────────────
        EmpaiaRemoteWsiImageServer server;
        try {
            server = new EmpaiaRemoteWsiImageServer(empaiaClient, md.id);
        } catch (Exception e) {
            logger.error("Failed to create image server for WSI {}", md.id, e);
            System.exit(1);
            return;
        }

        EmpaiaScriptApi api = new EmpaiaScriptApi(baseApi, jobId, token, md.id, httpClient);

        // ── 4. Start the script ───────────────────────────────────────────────
        api.start(scriptFile, server);
        logger.info("Script started — polling every {}ms", pollMs);

        // ── 5. Poll loop — forward progress to EMPAIA until done ─────────────
        double lastPostedProgress = -1;
        while (!api.isFinished()) {
            double progress = api.getProgress();
            if (progress != lastPostedProgress) {
                logger.info("Script progress: {}%", String.format("%.2f", progress * 100));
                putProgress(baseApi, jobId, token, progress, httpClient);
                lastPostedProgress = progress;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // ── 6. Finalize or fail ───────────────────────────────────────────────
        Throwable error = api.getError();
        if (error != null) {
            logger.error("Script execution failed", error);
            api.fail("Script execution failed: " + error.getMessage());
            System.exit(1);
        }

        putProgress(baseApi, jobId, token, 1.0, httpClient);
        api.finalizeJob();
        logger.info("Job finalized");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * PUT /{jobId}/progress with body {"progress": <fraction>} where fraction is in [0.0, 1.0]
     */
    private static void putProgress(String baseApi, String jobId, String token,
                                     double progress, HttpClient httpClient) {
        try {
            String url  = String.format("%s/%s/progress", baseApi, jobId);
            String body = String.format("{\"progress\":%s}", progress);

            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) req.header("Authorization", "Bearer " + token);

            HttpResponse<String> resp = httpClient.send(req.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                logger.warn("putProgress returned {}: {}", resp.statusCode(), resp.body());
            } else {
                logger.info("Progress PUT to EMPAIA: {} -> {}", progress, resp.statusCode());
            }
        } catch (Exception e) {
            logger.warn("putProgress failed (non-fatal): {}", e.getMessage());
        }
    }

    private static long parseLongEnv(String name, long defaultValue) {
        String val = System.getenv(name);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
