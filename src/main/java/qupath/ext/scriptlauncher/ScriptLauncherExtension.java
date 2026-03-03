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
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

public class ScriptLauncherExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(ScriptLauncherExtension.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private String baseApi;
    private String jobId;
    private String token;
    private QuPathGUI qupath;


    private String wsiId;

    @Override
    public void installExtension(QuPathGUI qupath) {
        this.qupath = qupath;
        this.baseApi = "https://testrat.dyn.cloud.e-infra.cz/api/app/v3";
        this.jobId = "2fa0a1fd-9734-47a7-b77b-909b92fba2c0";
        this.token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIyZmEwYTFmZC05NzM0LTQ3YTctYjc3Yi05MDliOTJmYmEyYzAiLCJleHAiOjE3NzI2Mzc1NjQsInRva2VuX2lkIjo2MX0.Q2FLgDbFN3YEdswkZV6jz5DEXgDbKUdPCTfYvMR7Vl1UEfuEpnohUjRw4aht5TWdDY1t0QbEa2fyKHGDuvSSkL0WcZWImR1PeIcPhA6iCJhDgGrHE3gnXT8XsbwpdJxlF0V9w9VfEddz6CZTVnJCLCwkS3ruk1KMWAtzq3r1AGk";

        if (baseApi == null || jobId == null) {
            logger.error("EMPAIA_APP_API and EMPAIA_JOB_ID must be set");
            return;
        }

        ImageData<BufferedImage> imageData = loadWsiFromEmpaia();
        if (imageData == null) {
            logger.error("Failed to load WSI, aborting");
            return;
        }

        fetchInputRoi(imageData);

        String scriptContent = loadScriptFromFile();
        if (scriptContent == null) {
            logger.error("Failed to load script, aborting");
            return;
        }

        Object scriptResult = executeScript(scriptContent);
        if (scriptResult == null) {
            return;
        }

        postOutputValues(scriptResult);
        postOutputAnnotations();

        finalizeJob();
    }

    private ImageData<BufferedImage> loadWsiFromEmpaia() {
        Map<String, String> headers = token != null ? Map.of("Authorization", "Bearer " + token) : Map.of();
        EmpaiaRemoteWsiClient empaiaClient = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);

        try {
            // Fetch metadata directly from inputs/slide which includes the WSI id
            EmpaiaRemoteWsiClient.Metadata md = empaiaClient.fetchMetadata();
            wsiId = md != null ? md.id : null;
            logger.info("Autodiscovered EMPAIA WSI id: {}", wsiId);

            // Validate metadata first to avoid unchecked IllegalArgumentException
            if (md == null || md.width <= 0 || md.height <= 0 || wsiId == null || wsiId.isEmpty()) {
                logger.error("Invalid EMPAIA metadata for wsiId={} width={} height={}",
                        wsiId, md == null ? -1 : md.width, md == null ? -1 : md.height);
                return null;
            }

            EmpaiaRemoteWsiImageServer server = new EmpaiaRemoteWsiImageServer(empaiaClient, wsiId);
            ImageData<BufferedImage> imageData = new ImageData<>(server);
            qupath.getViewer().setImageData(imageData);
            logger.info("Opened EMPAIA WSI {} via EmpaiaRemoteWsiImageServer", wsiId);
            return imageData;

        } catch (IOException | InterruptedException e) {
            logger.error("Failed to autodiscover or open EMPAIA WSI: " + e.getMessage(), e);
            return null;
        } catch (RuntimeException e) {
            logger.error("Failed to construct EmpaiaRemoteWsiImageServer for wsiId={}", wsiId, e);
            return null;
        }
    }

    private void fetchInputRoi(ImageData<BufferedImage> imageData) {
        try {
            String roiUrl = String.format("%s/%s/inputs/input_roi", baseApi, jobId);
            logger.info("Fetching input_roi from: {}", roiUrl);

            HttpRequest.Builder roiReqBuilder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(roiUrl))
                    .GET();

            if (token != null) {
                roiReqBuilder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> roiResp = httpClient.send(roiReqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (roiResp.statusCode() >= 200 && roiResp.statusCode() < 300) {
                String roiJson = roiResp.body();
                logger.info("Fetched input_roi: {}", roiJson);
                addRoiToHierarchy(imageData, roiJson);
            }
        } catch (Exception e) {
            logger.warn("Could not fetch input_roi: {}", e.getMessage());
        }
    }

    private String loadScriptFromFile() {
        String scriptPathEnv = System.getenv("QUPATH_SCRIPT");
        if (scriptPathEnv == null) {
            logger.error("No script found: QUPATH_SCRIPT env var not set");
            return null;
        }

        Path scriptPath = Path.of(scriptPathEnv);
        if (!Files.exists(scriptPath)) {
            logger.error("Script file not found: {}", scriptPath);
            return null;
        }

        try {
            String content = Files.readString(scriptPath);
            logger.info("Loaded script from file: {}", scriptPath);
            return content;
        } catch (IOException e) {
            logger.error("Failed to read script file {}", scriptPath, e);
            return null;
        }
    }

    private Object executeScript(String scriptContent) {
        if (scriptContent == null || scriptContent.isEmpty()) {
            logger.warn("Script content is empty");
            return null;
        }

        try {
            Path tempScript = Files.createTempFile("qupath-empaia-", ".groovy");
            Files.writeString(tempScript, scriptContent);

            Object result = qupath.runScript(tempScript.toFile(), null);
            logger.info("Successfully executed script");
            logger.info("Script returned: {}", result);

            Files.deleteIfExists(tempScript);
            return result;

        } catch (Exception e) {
            logger.error("Failed to execute script", e);
            reportFailure(e);
            return null;
        }
    }

    private void reportFailure(Exception exception) {
        try {
            String failureUrl = String.format("%s/%s/failure", baseApi, jobId);
            String failureBody = String.format(
                    "{\"user_message\":\"Script execution failed: %s\"}",
                    exception.getMessage().replace("\"", "'"));
            logger.info("Reporting failure to EMPAIA: {}", failureUrl);

            HttpRequest.Builder failReqBuilder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(failureUrl))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(failureBody));

            if (token != null) {
                failReqBuilder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> failResp = httpClient.send(failReqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            logger.info("Failure response: status {}, body: {}", failResp.statusCode(), failResp.body());
        } catch (Exception ex) {
            logger.error("Failed to report failure to EMPAIA", ex);
        }
    }

    private void postOutputValues(Object scriptResult) {
        try {
            int resultValue = 0;
            if (scriptResult instanceof Number) {
                resultValue = ((Number) scriptResult).intValue();
            } else if (scriptResult != null) {
                try {
                    resultValue = Integer.parseInt(scriptResult.toString());
                } catch (NumberFormatException e) {
                    logger.warn("Script result '{}' is not a valid integer, using 0", scriptResult);
                }
            } else {
                logger.info("Script returned null, using default value 0");
            }

            String outputUrl = String.format("%s/%s/outputs/output_values", baseApi, jobId);
            logger.info("Posting output_values with result {}", resultValue);

            String itemsJson = String.format(
                    "[{\"name\":\"result\",\"type\":\"float\",\"value\":%s,\"creator_id\":\"%s\",\"creator_type\":\"job\"}]",
                    String.valueOf((double) resultValue), jobId);

            String collectionBody = String.format(
                    "{\"type\":\"collection\",\"creator_id\":\"%s\",\"creator_type\":\"job\"," +
                    "\"item_type\":\"float\",\"reference_id\":\"%s\",\"reference_type\":\"wsi\",\"items\":%s}",
                    jobId, wsiId, itemsJson);

            HttpRequest.Builder outputReqBuilder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(outputUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(collectionBody));

            if (token != null) {
                outputReqBuilder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> outputResp = httpClient.send(outputReqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (outputResp.statusCode() >= 300) {
                logger.error("Output POST failed: status {}, body: {}", outputResp.statusCode(), outputResp.body());
            } else {
                logger.info("Output response: status {}, body: {}", outputResp.statusCode(), outputResp.body());
            }

        } catch (Exception e) {
            logger.error("Failed to send result to EMPAIA", e);
        }
    }

    private void postOutputAnnotations() {
        try {
            ImageData<BufferedImage> currentImageData = qupath.getViewer().getImageData();
            if (currentImageData == null) {
                logger.warn("No image data available for annotations");
                return;
            }

            var detections = currentImageData.getHierarchy().getDetectionObjects();
            logger.info("Found {} detection objects", detections.size());

            String annotationsUrl = String.format("%s/%s/outputs/output_annotations", baseApi, jobId);
            String annotationsBody;

            if (!detections.isEmpty()) {
                StringBuilder itemsArrayBuilder = new StringBuilder();
                int annotCount = 0;
                for (var detection : detections) {
                    var roi = detection.getROI();
                    if (roi == null || roi.getAllPoints().isEmpty()) continue;

                    var points = roi.getAllPoints();
                    StringBuilder coordsBuilder = new StringBuilder("[");
                    for (int i = 0; i < points.size(); i++) {
                        if (i > 0) coordsBuilder.append(",");
                        var point = points.get(i);
                        // Coordinates must be integers per the EMPAIA API schema
                        coordsBuilder.append(String.format("[%d,%d]", Math.round(point.getX()), Math.round(point.getY())));
                    }
                    coordsBuilder.append("]");

                    if (annotCount > 0) itemsArrayBuilder.append(",");
                    itemsArrayBuilder.append(String.format(
                            "{\"name\":\"detection_%d\"," +
                            "\"type\":\"polygon\"," +
                            "\"creator_id\":\"%s\"," +
                            "\"creator_type\":\"job\"," +
                            "\"reference_id\":\"%s\"," +
                            "\"reference_type\":\"wsi\"," +
                            "\"npp_created\":1000," +
                            "\"coordinates\":%s}",
                            annotCount, jobId, wsiId, coordsBuilder.toString()));
                    annotCount++;
                }

                annotationsBody = String.format(
                        "{\"type\":\"collection\"," +
                        "\"creator_id\":\"%s\"," +
                        "\"creator_type\":\"job\"," +
                        "\"item_type\":\"polygon\"," +
                        "\"reference_id\":\"%s\"," +
                        "\"reference_type\":\"wsi\"," +
                        "\"items\":[%s]}",
                        jobId, wsiId, itemsArrayBuilder.toString());
                logger.info("Posting {} polygon annotation(s) to output_annotations", annotCount);
            } else {
                annotationsBody = String.format(
                        "{\"type\":\"collection\",\"creator_id\":\"%s\",\"creator_type\":\"job\"," +
                        "\"item_type\":\"polygon\",\"reference_id\":\"%s\",\"reference_type\":\"wsi\",\"items\":[]}",
                        jobId, wsiId);
                logger.info("Posting empty output_annotations collection (no detections)");
            }

            HttpRequest.Builder annotReqBuilder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(annotationsUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(annotationsBody));

            if (token != null) {
                annotReqBuilder.header("Authorization", "Bearer " + token);
            }

            logger.info("Annotations POST payload: {}", annotationsBody);
            HttpResponse<String> annotResp = httpClient.send(annotReqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (annotResp.statusCode() >= 300) {
                logger.error("Annotations POST failed: status {}, body: {}",
                        annotResp.statusCode(), annotResp.body());
            } else {
                logger.info("Annotations output response: status {}, body: {}",
                        annotResp.statusCode(), annotResp.body());
            }

        } catch (Exception e) {
            logger.error("Failed to send annotations to EMPAIA", e);
        }
    }

    private void finalizeJob() {
        try {
            String finalizeUrl = String.format("%s/%s/finalize", baseApi, jobId);
            logger.info("Finalizing job: {}", finalizeUrl);

            HttpRequest.Builder finalizeReqBuilder = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(finalizeUrl))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{}"));

            if (token != null) {
                finalizeReqBuilder.header("Authorization", "Bearer " + token);
            }

            HttpResponse<String> finalizeResp = httpClient.send(finalizeReqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            logger.info("Finalize response: status {}, body: {}", finalizeResp.statusCode(), finalizeResp.body());
        } catch (Exception e) {
            logger.error("Failed to finalize job", e);
        }
    }

    private void addRoiToHierarchy(ImageData<BufferedImage> imageData, String roiJson) {
        int upperLeftStart = roiJson.indexOf("\"upper_left\":[") + 14;
        int upperLeftEnd = roiJson.indexOf("]", upperLeftStart);
        String[] coords = roiJson.substring(upperLeftStart, upperLeftEnd).split(",");
        double x = Double.parseDouble(coords[0].trim());
        double y = Double.parseDouble(coords[1].trim());

        int widthStart = roiJson.indexOf("\"width\":") + 8;
        int widthEnd = roiJson.indexOf(",", widthStart);
        double width = Double.parseDouble(roiJson.substring(widthStart, widthEnd).trim());

        int heightStart = roiJson.indexOf("\"height\":") + 9;
        int heightEnd = roiJson.indexOf(",", heightStart);
        double height = Double.parseDouble(roiJson.substring(heightStart, heightEnd).trim());

        ROI roi = ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane());

        PathObject annotation = PathObjects.createAnnotationObject(roi);
        annotation.setName("input_roi");

        imageData.getHierarchy().addObject(annotation);
        logger.info("Added input_roi annotation to hierarchy: x={}, y={}, w={}, h={}",
                x, y, width, height);
    }

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
