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
import java.util.Map;

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
        String imagePath = System.getenv("QUPATH_IMAGE");
        if (imagePath == null) {
            logger.warn("QUPATH_IMAGE not set");
            return;
        }
        File f = new File(imagePath);
        if (!f.exists()) {
            logger.error("Image file not found: " + imagePath);
            return;
        }
        try {
            qupath.openImage(qupath.getViewer(), imagePath);
            logger.info("Opened image via OpenSlide: " + imagePath);
        } catch (IOException e) {
            logger.error("Failed to open image with OpenSlide", e);
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
