package qupath.ext.scriptlauncher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import java.awt.image.BufferedImage;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiClient;
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiImageServer;

public class ScriptLauncherExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ScriptLauncherExtension.class);
    private final String appApi = "https://testrat.dyn.cloud.e-infra.cz/api/app";
    private final String jobId = "f1344441-bc42-4f39-820c-9034317bc5e0";
    private final String token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJmMTM0NDQ0MS1iYzQyLTRmMzktODIwYy05MDM0MzE3YmM1ZTAiLCJleHAiOjE3NTk0MDYyNjgsInRva2VuX2lkIjoxfQ.W79eoBhu4t86g7jY9H2SPR0fggmOvYIrdV1KBs8SCEdBIcb3KE8Y52OQQVjomeoDibCEYzhQt5GPiRWtovGd-WiCKRzEjFedmgsri9YP-bCe2XUhTANDZQ32FmyqZMoljCnoKnCr945qU-O9OuikZUyLi82juVMKfsXkH2o6YMo";
    private final Map<String, String> headers = Map.of(
        "Authorization", "Bearer " + token
    );

    private final HttpClient client = HttpClient.newHttpClient();
   
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

        Map<String,String> headers = token != null ? Map.of("Authorization", "Bearer " + token) : Map.of();
        EmpaiaRemoteWsiClient client = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);

        try {
            // Always autodiscover pixelmaps from the EMPAIA API. Do not allow
            // operator-supplied EMPAIA_PIXELMAP_ID/EMPAIA_WSI_ID overrides.
            List<String> pixelmaps = client.listPixelmaps();
            if (pixelmaps == null || pixelmaps.isEmpty()) {
                logger.error("No pixelmaps found for job {}", jobId);
                return;
            }

            // If multiple pixelmaps are present, pick the first one. We could
            // later expose a chooser in the UI if needed.
            String wsiId = pixelmaps.get(0);
            logger.info("Autodiscovered EMPAIA WSI id(s): {}. Using {}", pixelmaps, wsiId);
            URI uri = URI.create(wsiId);

            // Validate metadata first to avoid unchecked IllegalArgumentException
            // thrown by ImageServerMetadata.Builder when width/height are invalid.
            EmpaiaRemoteWsiClient.Metadata md = client.fetchMetadata(wsiId);
            if (md == null || md.width <= 0 || md.height <= 0) {
                logger.error("Invalid EMPAIA metadata for wsiId={}: width={}, height={}. Aborting open.", wsiId,
                        md == null ? -1 : md.width, md == null ? -1 : md.height);
                return;
            }

            // Build server directly and open in the current viewer
            try {
                EmpaiaRemoteWsiImageServer server = new EmpaiaRemoteWsiImageServer(uri);
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

        String scriptPath = System.getenv("QUPATH_SCRIPT");
         if (scriptPath == null) {
            logger.warn("QUPATH_SCRIPT not set");
            return;
        }

        File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            logger.error("Script file not found: " + scriptPath);
            return;
        }

        try {
            qupath.runScript(scriptFile, null);
            logger.info("Executed script: " + scriptPath);
        } catch (Exception e) {
            logger.error("Failed to execute script", e);
        }

        try {
            HttpResponse<String> response = getMode();
            int status = response.statusCode();
            String body = response.body();

            if (status >= 400) {
                logger.error("Request failed: " + status);
            } else {
                logger.info("Response: " + body);
            }
        } catch (IOException | InterruptedException e) {
            logger.error("HTTP request failed", e);
        }
    }

    public HttpResponse<String> getMode() throws IOException, InterruptedException {
    String url = String.format("%s/v3/%s/mode", appApi, jobId);

    HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET();

    headers.forEach(builder::header);

    HttpRequest request = builder.build();

    return client.send(request, HttpResponse.BodyHandlers.ofString());
    }


    @Override
    public String getName() {
        return "Script Launcher Extension";
    }

    public String getDescription() {
        return "Runs custom actions automatically at startup.";
    }
}
