package qupath.ext.scriptlauncher;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiClient;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiImageServer;

import java.io.File;
import java.net.http.HttpClient;
import java.util.Map;

/**
 * EmpaiaScriptManager — main entry point for headless EMPAIA job execution.
 *
 * <p>Replaces {@code launcher.groovy} as the Docker entrypoint. Invoked as:
 * <pre>
 *   java -cp "/opt/QuPath/lib/app/*" qupath.ext.scriptlauncher.EmpaiaScriptManager
 * </pre>
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code EMPAIA_BASE_API} — EMPAIA App API base URL (e.g. https://host/api/app/v3)</li>
 *   <li>{@code EMPAIA_JOB_ID}   — EMPAIA job UUID</li>
 *   <li>{@code QUPATH_SCRIPT}   — absolute path to the user Groovy script to run</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code EMPAIA_TOKEN} — bearer token for authenticated access</li>
 * </ul>
 */
public class EmpaiaScriptManager {

    private static final Logger logger = LoggerFactory.getLogger(EmpaiaScriptManager.class);

    public static void main(String[] args) {
        // ── 1. Read configuration from environment ────────────────────────────
        String baseApi    = System.getenv("EMPAIA_BASE_API");
        String jobId      = System.getenv("EMPAIA_JOB_ID");
        String token      = System.getenv("EMPAIA_TOKEN");
        String scriptPath = System.getenv("QUPATH_SCRIPT");

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

        // ── 2. Connect to EMPAIA and open WSI ─────────────────────────────────
        Map<String, String> headers = token != null
                ? Map.of("Authorization", "Bearer " + token)
                : Map.of();

        EmpaiaRemoteWsiClient client = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);
        EmpaiaRemoteWsiClient.Metadata md;
        try {
            md = client.fetchMetadata();
        } catch (Exception e) {
            logger.error("Failed to fetch WSI metadata from EMPAIA", e);
            System.exit(1);
            return;
        }

        if (md == null || md.width <= 0 || md.height <= 0 || md.id == null) {
            logger.error("Invalid WSI metadata from EMPAIA");
            System.exit(1);
        }

        ImageData<?> imageData;
        try {
            EmpaiaRemoteWsiImageServer server = new EmpaiaRemoteWsiImageServer(client, md.id);
            imageData = new ImageData<>(server);
        } catch (Exception e) {
            logger.error("Failed to create image server for WSI {}", md.id, e);
            System.exit(1);
            return;
        }
        logger.info("Opened EMPAIA WSI: {}", md.id);

        // ── 3. Create EmpaiaScriptApi  ─────────────────────────────────────────
        EmpaiaScriptApi api = new EmpaiaScriptApi(
                baseApi, jobId, token, md.id, HttpClient.newHttpClient());

        // ── 4. Fetch input ROI and add to hierarchy ───────────────────────────
        var inputRoi = api.getInputRoi();
        if (inputRoi != null) {
            imageData.getHierarchy().addObject(inputRoi);
            logger.info("Added input_roi to hierarchy");
        }

        // ── 5. Execute user script with ScriptApi injected into Groovy binding ─
        // 'api', 'imageData' and 'hierarchy' are available in the script as
        // plain variables — no imports required. The class loader is shared so
        // all QuPath and extension classes are accessible from the script.
        Binding binding = new Binding();
        binding.setVariable("api",       api);
        binding.setVariable("imageData", imageData);
        binding.setVariable("hierarchy", imageData.getHierarchy());

        try {
            new GroovyShell(EmpaiaScriptManager.class.getClassLoader(), binding)
                    .evaluate(scriptFile);
            logger.info("User script completed successfully");
        } catch (Exception e) {
            api.fail("Script execution failed: " + e.getMessage());
            logger.error("Script execution failed", e);
            System.exit(1);
        }

        // ── 6. Finalize the EMPAIA job ────────────────────────────────────────
        api.finalizeJob();
        logger.info("Job finalized");
    }
}
