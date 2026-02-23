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
        String baseApi = "https://testrat.dyn.cloud.e-infra.cz/api/app/v3";
        String jobId = "dde46b06-3e66-4440-bdaa-698f92748db0";
        String token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJkZGU0NmIwNi0zZTY2LTQ0NDAtYmRhYS02OThmOTI3NDhkYjAiLCJleHAiOjE3NzE1OTkxNzQsInRva2VuX2lkIjoxMn0.QIvHMR0uPOMSYsUt93ajp-94hGXdshV62NKYzmOaBPDFxg09n_x9sL8NpIPusIoB5k22Bq6CpEfCFknELc97djLcVne8N-JXoUhS__DBAMORUGuO0ky4hjoeKRpCvB2hKgnwjW1FpnpRswTSxA6L2zglqZN34dUHgFyLZEsrHtQ";

        if (baseApi == null || jobId == null) {
            logger.error("EMPAIA_APP_API and EMPAIA_JOB_ID must be set for autodiscovery");
            return;
        }

        Map<String, String> headers = token != null ? Map.of("Authorization", "Bearer " + token) : Map.of();
        EmpaiaRemoteWsiClient empaiaClient = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);

        try {
            // Fetch metadata directly from inputs/slide which includes the WSI id
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
            logger.error("Failed to autodiscover or open EMPAIA WSI: " + e.getMessage(), e);
            return;
        }

        // Try to fetch script from EMPAIA /inputs/my_script first
        String scriptContent = null;
        String scriptSource = null;
        
        try {
            String scriptUrl = String.format("%s/%s/inputs/script", baseApi, jobId);
            logger.info("Fetching script from EMPAIA: {}", scriptUrl);
            
            HttpRequest.Builder scriptReqBuilder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(scriptUrl))
                    .GET();
            
            if (token != null) {
                scriptReqBuilder.header("Authorization", "Bearer " + token);
            }
            
            HttpResponse<String> scriptResp = httpClient.send(scriptReqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            
            if (scriptResp.statusCode() >= 200 && scriptResp.statusCode() < 300) {
                // Parse JSON response to extract primitive value
                String jsonResponse = scriptResp.body();
                logger.debug("Received script response: {}", jsonResponse);
                
                // Simple JSON parsing to extract "value" field
                // Format: {"id":"...", "type":"string", "value":"script content here", ...}
                int valueStart = jsonResponse.indexOf("\"value\":");
                if (valueStart > 0) {
                    valueStart = jsonResponse.indexOf("\"", valueStart + 8) + 1;
                    int valueEnd = jsonResponse.indexOf("\"", valueStart);
                    // Handle escaped quotes
                    while (valueEnd > 0 && jsonResponse.charAt(valueEnd - 1) == '\\') {
                        valueEnd = jsonResponse.indexOf("\"", valueEnd + 1);
                    }
                    if (valueEnd > valueStart) {
                        scriptContent = jsonResponse.substring(valueStart, valueEnd);
                        // Unescape JSON string
                        scriptContent = scriptContent
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                                .replace("\\t", "\t");
                        scriptSource = "EMPAIA /inputs/script";
                        logger.info("Loaded script from EMPAIA ({} characters)", scriptContent.length());
                    }
                }
                
                if (scriptContent == null) {
                    logger.warn("Could not extract 'value' from EMPAIA response");
                }
            } else {
                logger.warn("EMPAIA script fetch returned status {}", scriptResp.statusCode());
            }
        } catch (Exception e) {
            logger.warn("Could not fetch script from EMPAIA: {}", e.getMessage());
        }

        // Fallback to QUPATH_SCRIPT environment variable
        if (scriptContent == null) {
            String scriptPathEnv = System.getenv("QUPATH_SCRIPT");
            if (scriptPathEnv == null) {
                logger.error("No script found: neither EMPAIA /inputs/script nor QUPATH_SCRIPT env var available");
                return;
            }

            Path scriptPath = Path.of(scriptPathEnv);
            if (!Files.exists(scriptPath)) {
                logger.error("Script file not found: {}", scriptPath);
                return;
            }
            
            try {
                scriptContent = Files.readString(scriptPath);
                scriptSource = "QUPATH_SCRIPT: " + scriptPath;
                logger.info("Loaded script from file: {}", scriptPath);
            } catch (IOException e) {
                logger.error("Failed to read script file {}", scriptPath, e);
                return;
            }
        }

        // Execute the script
        if (scriptContent != null && !scriptContent.isEmpty()) {
            Object scriptResult = null;
            try {
                // Write to temporary file and execute
                Path tempScript = Files.createTempFile("qupath-empaia-", ".groovy");
                Files.writeString(tempScript, scriptContent);
                
                scriptResult = qupath.runScript(tempScript.toFile(), null);
                logger.info("Successfully executed script from: {}", scriptSource);
                logger.info("Script returned: {}", scriptResult);
                
                // Clean up temp file
                Files.deleteIfExists(tempScript);
            } catch (Exception e) {
                logger.error("Failed to execute script from {}", scriptSource, e);
            }
            
            // Send result back to EMPAIA /outputs/test
            if (scriptResult != null) {
                try {
                    Integer resultValue = null;
                    
                    // Convert result to integer
                    if (scriptResult instanceof Integer) {
                        resultValue = (Integer) scriptResult;
                    } else if (scriptResult instanceof Number) {
                        resultValue = ((Number) scriptResult).intValue();
                    } else {
                        try {
                            resultValue = Integer.parseInt(scriptResult.toString());
                        } catch (NumberFormatException e) {
                            logger.warn("Script result '{}' is not a valid integer", scriptResult);
                        }
                    }
                    
                    if (resultValue != null) {
                        // POST complete collection with items
                        String outputUrl = String.format("%s/%s/outputs/output_values", baseApi, jobId);
                        logger.info("Posting output_values collection with result {}", resultValue);
                        
                        String collectionBody = String.format(
                            "{\"type\": \"collection\", " +
                            "\"creator_id\": \"%s\", " +
                            "\"creator_type\": \"job\", " +
                            "\"item_type\": \"float\", " +
                            "\"items\": [{" +
                                "\"name\": \"result\", " +
                                "\"type\": \"float\", " +
                                "\"value\": %d, " +
                                "\"creator_id\": \"%s\", " +
                                "\"creator_type\": \"job\"" +
                            "}]}",
                            jobId, resultValue, jobId
                        );
                        
                        HttpRequest.Builder outputReqBuilder = HttpRequest.newBuilder()
                                .uri(java.net.URI.create(outputUrl))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(collectionBody));
                        
                        if (token != null) {
                            outputReqBuilder.header("Authorization", "Bearer " + token);
                        }
                        
                        HttpResponse<String> outputResp = httpClient.send(outputReqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                        logger.info("Output response: status {}, body: {}", outputResp.statusCode(), outputResp.body());
                    }
                } catch (Exception e) {
                    logger.error("Failed to send result to EMPAIA", e);
                }
            } else {
                logger.warn("Script returned null, no result to send to EMPAIA");
            }
        } else {
            logger.warn("Script content is empty");
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
