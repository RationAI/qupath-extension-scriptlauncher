package qupath.ext.scriptlauncher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import java.awt.image.BufferedImage;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiClient;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiImageServer;

public class ScriptLauncherExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ScriptLauncherExtension.class);

    // Use a single HttpClient instance for extension-level HTTP calls
    private final HttpClient httpClient = HttpClient.newHttpClient();
   
    @Override
    public void installExtension(QuPathGUI qupath) {
        // Autodiscover WSI from EMPAIA API (do not read QUPATH_IMAGE)
        String baseApi = System.getenv("EMPAIA_APP_API");
        String jobId = System.getenv("EMPAIA_JOB_ID");
        String token = System.getenv("EMPAIA_TOKEN");

        if (baseApi == null || jobId == null) {
            logger.error("EMPAIA_APP_API and EMPAIA_JOB_ID must be set for autodiscovery");
            return;
        }

        Map<String, String> headers = token != null ? Map.of("Authorization", "Bearer " + token) : Map.of();
        EmpaiaRemoteWsiClient empaiaClient = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);

        try {
            // Fetch metadata directly from inputs/my_wsi which includes the WSI id
            EmpaiaRemoteWsiClient.Metadata md = empaiaClient.fetchMetadata();
            String wsiId = md != null ? md.id : null;
            logger.info("Autodiscovered EMPAIA WSI id: {}", wsiId);

            // Validate metadata first to avoid unchecked IllegalArgumentException
            // thrown by ImageServerMetadata.Builder when width/height are invalid.
            if (md == null || md.width <= 0 || md.height <= 0 || wsiId == null || wsiId.isEmpty()) {
                logger.error("Invalid EMPAIA metadata for wsiId={} width={} height= {}",
                        wsiId, md == null ? -1 : md.width, md == null ? -1 : md.height);
                return;
            }

            // Build server directly and open in the current viewer. Prefer the
            // constructor that takes a client + wsiId to avoid creating a URI
            // from a bare identifier (which can cause "Missing scheme").
            try {
                EmpaiaRemoteWsiImageServer server = new EmpaiaRemoteWsiImageServer(empaiaClient, wsiId);
                ImageData<BufferedImage> imageData = new ImageData<>(server);
                qupath.getViewer().setImageData(imageData);
                logger.info("Opened EMPAIA WSI {} via EmpaiaRemoteWsiImageServer", wsiId);
            } catch (RuntimeException e) {
                // Catch unchecked exceptions (e.g. IllegalArgumentException) to avoid
                // crashing the extension loader; log and abort gracefully.
                logger.error("Failed to construct EmpaiaRemoteWsiImageServer for wsiId={}", wsiId, e);
                return;
            }

        } catch (IOException | InterruptedException e) {
            logger.error("Failed to autodiscover or open EMPAIA WSI", e);
            return;
        }

        String scriptPathEnv = System.getenv("QUPATH_SCRIPT");
        if (scriptPathEnv == null) {
            logger.warn("QUPATH_SCRIPT not set");
            return;
        }

        Path scriptPath = Path.of(scriptPathEnv);
        if (!Files.exists(scriptPath)) {
            logger.error("Script file not found: {}", scriptPath);
            return;
        }

        try {
            qupath.runScript(scriptPath.toFile(), null);
            logger.info("Executed script: {}", scriptPath);
        } catch (Exception e) {
            logger.error("Failed to execute script {}", scriptPath, e);
        }

        try {
            HttpResponse<String> response = getMode(baseApi, jobId);
            int status = response.statusCode();
            String body = response.body();

            if (status >= 400) {
                logger.error("Request failed: {}", status);
            } else {
                logger.info("Response: {}", body);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("HTTP request failed", e);
        }
    }

    /**
     * Query the EMPAIA mode endpoint for the provided job.
     *
     * @param baseApi base app API URL (e.g. https://host/api/app)
     * @param jobId   EMPAIA job id
     */
    public HttpResponse<String> getMode(String baseApi, String jobId) throws IOException, InterruptedException {
        String url = String.format("%s/%s/mode", baseApi, jobId);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET();

        HttpRequest request = builder.build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }


    @Override
    public String getName() {
        return "Script Launcher Extension";
    }

    public String getDescription() {
        return "Runs custom actions automatically at startup.";
    }
}
