package qupath.ext.scriptlauncher;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiClient;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiImageServer;

import java.awt.image.BufferedImage;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * QuPath GUI extension entry point — for local development and testing only.
 *
 * <p>In production the headless {@code launcher.groovy} script is used instead.
 * Configuration is read exclusively from environment variables so that no
 * credentials are ever hardcoded.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code EMPAIA_BASE_API} — EMPAIA App API base URL</li>
 *   <li>{@code EMPAIA_JOB_ID}   — EMPAIA job UUID</li>
 *   <li>{@code QUPATH_SCRIPT}   — absolute path to the user Groovy script</li>
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code EMPAIA_TOKEN} — bearer token (omit for unauthenticated access)</li>
 * </ul>
 */
public class ScriptLauncherExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ScriptLauncherExtension.class);

    @Override
    public void installExtension(QuPathGUI qupath) {
        String baseApi = System.getenv("EMPAIA_BASE_API");
        String jobId   = System.getenv("EMPAIA_JOB_ID");
        String token   = System.getenv("EMPAIA_TOKEN");

        if (baseApi == null || jobId == null) {
            logger.error("EMPAIA_BASE_API and EMPAIA_JOB_ID environment variables must be set");
            return;
        }

        // ── 1. Open WSI from EMPAIA ──────────────────────────────────────────
        Map<String, String> headers = token != null
                ? Map.of("Authorization", "Bearer " + token) : Map.of();
        EmpaiaRemoteWsiClient empaiaClient = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);

        String wsiId;
        ImageData<BufferedImage> imageData;
        try {
            EmpaiaRemoteWsiClient.Metadata md = empaiaClient.fetchMetadata();
            wsiId = md != null ? md.id : null;
            if (md == null || md.width <= 0 || md.height <= 0 || wsiId == null) {
                logger.error("Invalid EMPAIA WSI metadata");
                return;
            }
            EmpaiaRemoteWsiImageServer server = new EmpaiaRemoteWsiImageServer(empaiaClient, wsiId);
            imageData = new ImageData<>(server);
            qupath.getViewer().setImageData(imageData);
            logger.info("Opened EMPAIA WSI {}", wsiId);
        } catch (Exception e) {
            logger.error("Failed to load WSI from EMPAIA", e);
            return;
        }

        // ── 2. Create EmpaiaScriptApi ────────────────────────────────────────
        EmpaiaScriptApi api = new EmpaiaScriptApi(baseApi, jobId, token, wsiId,
                HttpClient.newHttpClient());

        // ── 3. Fetch input ROI and add to hierarchy ──────────────────────────
        var inputRoi = api.getInputRoi();
        if (inputRoi != null) {
            imageData.getHierarchy().addObject(inputRoi);
            logger.info("Added input_roi to hierarchy");
        }

        // ── 4. Load user script ──────────────────────────────────────────────
        String scriptPathEnv = System.getenv("QUPATH_SCRIPT");
        if (scriptPathEnv == null) {
            logger.error("QUPATH_SCRIPT environment variable not set");
            api.failJob("QUPATH_SCRIPT not set");
            return;
        }
        Path scriptPath = Path.of(scriptPathEnv);
        if (!Files.exists(scriptPath)) {
            logger.error("Script file not found: {}", scriptPath);
            api.failJob("Script not found: " + scriptPathEnv);
            return;
        }

        // ── 5. Execute user script with api injected into Groovy binding ─────
        try {
            Binding binding = new Binding();
            binding.setVariable("api",       api);
            binding.setVariable("imageData", imageData);
            binding.setVariable("hierarchy", imageData.getHierarchy());
            binding.setVariable("server", imageData.getServer());
            binding.setVariable("selectionModel", imageData.getHierarchy().getSelectionModel());
            binding.setVariable("project", null);
            binding.setVariable("args", new String[0]);

            new GroovyShell(getClass().getClassLoader(), binding)
                    .evaluate(scriptPath.toFile());
            logger.info("Script completed successfully");
        } catch (Exception e) {
            logger.error("Script execution failed", e);
            api.failJob("Script execution failed: " + e.getMessage());
            return;
        }

        // ── 6. Finalize job ──────────────────────────────────────────────────
        api.finalizeJob();
    }

    @Override
    public String getName() {
        return "Script Launcher Extension";
    }

    @Override
    public String getDescription() {
        return "Launches EMPAIA job scripts automatically at startup (GUI dev mode).";
    }
}
