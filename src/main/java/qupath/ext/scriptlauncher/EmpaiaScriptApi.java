package qupath.ext.scriptlauncher;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import qupath.ext.script.api.ScriptApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * EMPAIA implementation of {@link ScriptApi} that communicates with the
 * EMPAIA App API over HTTP.
 *
 * <p>One instance covers the lifetime of a single job run. Construct it with
 * the job credentials obtained from the environment, then inject it into the
 * user script via a Groovy {@code Binding} under the name {@code api}.
 */
public class EmpaiaScriptApi implements ScriptApi {

    private static final Logger logger = LoggerFactory.getLogger(EmpaiaScriptApi.class);

    private final HttpClient httpClient;
    private final String baseApi;
    private final String jobId;
    private final String token;
    private final String wsiId;

    /** Cached result of {@link #getInputRoi()} — fetched at most once. */
    private PathObject inputRoi;
    private boolean inputRoiFetched = false;

    /** Progress fraction [0.0, 1.0] reported by the running script. */
    private final AtomicReference<Double> progress = new AtomicReference<>(0.0);

    /** Background executor for the Groovy script. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "script-runner");
        t.setDaemon(true);
        return t;
    });
    private Future<?> future;

    /**
     * @param baseApi    EMPAIA App API base URL, e.g. {@code https://host/api/app/v3}
     * @param jobId      EMPAIA job ID (UUID string)
     * @param token      Bearer token for authentication, or {@code null} if not required
     * @param wsiId      WSI UUID obtained from EMPAIA inputs
     * @param httpClient shared HTTP client
     */
    public EmpaiaScriptApi(String baseApi, String jobId, String token, String wsiId, HttpClient httpClient) {
        this.baseApi = baseApi;
        this.jobId = jobId;
        this.token = token;
        this.wsiId = wsiId;
        this.httpClient = httpClient;
    }

    // -------------------------------------------------------------------------
    // ScriptApi interface
    // -------------------------------------------------------------------------

    // ── Script lifecycle (ScriptApi) ──────────────────────────────────────────

    @Override
    public void start(File script, ImageServer<?> server) {
        if (future != null) {
            throw new IllegalStateException("Runner has already been started");
        }
        future = executor.submit(() -> runScript(script, server));
    }

    @Override
    public double getProgress() {
        return progress.get();
    }

    @Override
    public boolean isFinished() {
        return future != null && future.isDone();
    }

    @Override
    public Throwable getError() {
        if (future == null || !future.isDone()) return null;
        try {
            future.get();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    @Override
    public void reportProgress(double fraction) {
        progress.set(Math.max(0.0, Math.min(1.0, fraction)));
    }

    @Override
    public PathObject getInputRoi() {
        if (!inputRoiFetched) {
            inputRoi = fetchInputRoi();
            inputRoiFetched = true;
        }
        return inputRoi;
    }

    @Override
    public void postValues(String outputKey, Collection<? extends Number> values) {
        try {
            String outputUrl = String.format("%s/%s/outputs/%s", baseApi, jobId, outputKey);

            StringBuilder itemsBuilder = new StringBuilder("[");
            boolean first = true;
            for (Number v : values) {
                if (!first) itemsBuilder.append(",");
                itemsBuilder.append(String.format(
                        "{\"name\":\"result\",\"type\":\"float\",\"value\":%s," +
                        "\"creator_id\":\"%s\",\"creator_type\":\"job\"}",
                        String.valueOf(v.doubleValue()), jobId));
                first = false;
            }
            itemsBuilder.append("]");

            // PostFloatCollection: "type" must be the literal "collection";
            // "item_type":"float" routes the union discriminator.
            String body = String.format(
                    "{\"type\":\"collection\",\"creator_id\":\"%s\",\"creator_type\":\"job\"," +
                    "\"item_type\":\"float\",\"reference_id\":\"%s\",\"reference_type\":\"wsi\"," +
                    "\"items\":%s}",
                    jobId, wsiId, itemsBuilder);

            int status = post(outputUrl, body);
            if (status >= 300) {
                logger.error("postValues failed: key={} status={}", outputKey, status);
            } else {
                logger.info("postValues OK: key={} count={} status={}", outputKey, values.size(), status);
            }
        } catch (Exception e) {
            logger.error("postValues exception: key={}", outputKey, e);
        }
    }

    @Override
    public void postAnnotations(String outputKey, Collection<PathObject> detections) {
        try {
            String outputUrl = String.format("%s/%s/outputs/%s", baseApi, jobId, outputKey);

            StringBuilder itemsBuilder = new StringBuilder();
            int count = 0;
            for (PathObject detection : detections) {
                ROI roi = detection.getROI();
                if (roi == null || roi.getAllPoints().isEmpty()) continue;

                var points = roi.getAllPoints();
                StringBuilder coordsBuilder = new StringBuilder("[");
                for (int i = 0; i < points.size(); i++) {
                    if (i > 0) coordsBuilder.append(",");
                    var p = points.get(i);
                    // EMPAIA requires integer pixel coordinates
                    coordsBuilder.append(String.format("[%d,%d]",
                            Math.round(p.getX()), Math.round(p.getY())));
                }
                coordsBuilder.append("]");

                if (count > 0) itemsBuilder.append(",");
                // PostPolygonAnnotation: "type":"polygon"
                itemsBuilder.append(String.format(
                        "{\"name\":\"detection_%d\",\"type\":\"polygon\"," +
                        "\"creator_id\":\"%s\",\"creator_type\":\"job\"," +
                        "\"reference_id\":\"%s\",\"reference_type\":\"wsi\"," +
                        "\"npp_created\":1000,\"coordinates\":%s}",
                        count, jobId, wsiId, coordsBuilder));
                count++;
            }

            // PostPolygonCollection: "type" must be "collection";
            // "item_type":"polygon" routes the union discriminator.
            String body = String.format(
                    "{\"type\":\"collection\",\"creator_id\":\"%s\",\"creator_type\":\"job\"," +
                    "\"item_type\":\"polygon\",\"reference_id\":\"%s\",\"reference_type\":\"wsi\"," +
                    "\"items\":[%s]}",
                    jobId, wsiId, itemsBuilder);

            int status = post(outputUrl, body);
            if (status >= 300) {
                logger.error("postAnnotations failed: key={} status={}", outputKey, status);
            } else {
                logger.info("postAnnotations OK: key={} count={} status={}", outputKey, count, status);
            }
        } catch (Exception e) {
            logger.error("postAnnotations exception: key={}", outputKey, e);
        }
    }

    @Override
    public void fail(String message) {
        try {
            String url = String.format("%s/%s/failure", baseApi, jobId);
            String body = String.format(
                    "{\"user_message\":\"%s\"}",
                    message.replace("\"", "'"));
            int status = put(url, body);
            logger.info("fail reported: status={}", status);
        } catch (Exception e) {
            logger.error("fail reporting exception", e);
        }
    }

    // -------------------------------------------------------------------------
    // Package-level: called by the launcher bootstrap, not part of the script API
    // -------------------------------------------------------------------------

    /**
     * Finalizes the job. Called by the launcher after the user script completes
     * successfully. Not exposed on the public {@link ScriptApi} interface.
     */
    public void finalizeJob() {
        try {
            String url = String.format("%s/%s/finalize", baseApi, jobId);
            int status = put(url, "{}");
            if (status >= 300) {
                logger.error("finalizeJob failed: status={}", status);
            } else {
                logger.info("finalizeJob OK: status={}", status);
            }
        } catch (Exception e) {
            logger.error("finalizeJob exception", e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void runScript(File script, ImageServer<?> server) {
        ImageData<?> imageData = new ImageData<>(server);
        var inputRoi = getInputRoi();
        if (inputRoi != null) {
            imageData.getHierarchy().addObject(inputRoi);
            logger.info("Added input_roi to hierarchy");
        }

        Binding binding = new Binding();
        binding.setVariable("api",       this);
        binding.setVariable("imageData", imageData);
        binding.setVariable("hierarchy", imageData.getHierarchy());
        binding.setVariable("server", imageData.getServer());
        binding.setVariable("selectionModel", imageData.getHierarchy().getSelectionModel());
        binding.setVariable("project", null);
        binding.setVariable("args", new String[0]);

        logger.info("Starting script: {}", script.getName());
        try {
            new GroovyShell(getClass().getClassLoader(), binding).evaluate(script);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read script file: " + script.getName(), e);
        }
        logger.info("Script finished: {}", script.getName());
    }

    private PathObject fetchInputRoi() {
        try {
            String url = String.format("%s/%s/inputs/input_roi", baseApi, jobId);
            logger.info("Fetching input_roi from: {}", url);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET();
            if (token != null) builder.header("Authorization", "Bearer " + token);

            HttpResponse<String> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                logger.info("Fetched input_roi: {}", resp.body());
                return parseRoiJson(resp.body());
            } else {
                logger.warn("input_roi fetch returned status {}", resp.statusCode());
            }
        } catch (Exception e) {
            logger.warn("Could not fetch input_roi: {}", e.getMessage());
        }
        return null;
    }

    private PathObject parseRoiJson(String json) {
        int upperLeftStart = json.indexOf("\"upper_left\":[") + 14;
        int upperLeftEnd = json.indexOf("]", upperLeftStart);
        String[] coords = json.substring(upperLeftStart, upperLeftEnd).split(",");
        double x = Double.parseDouble(coords[0].trim());
        double y = Double.parseDouble(coords[1].trim());

        int widthStart = json.indexOf("\"width\":") + 8;
        int widthEnd = json.indexOf(",", widthStart);
        double width = Double.parseDouble(json.substring(widthStart, widthEnd).trim());

        int heightStart = json.indexOf("\"height\":") + 9;
        int heightEnd = json.indexOf(",", heightStart);
        double height = Double.parseDouble(json.substring(heightStart, heightEnd).trim());

        ROI roi = ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane());
        PathObject annotation = PathObjects.createAnnotationObject(roi);
        annotation.setName("input_roi");
        logger.info("Parsed input_roi: x={} y={} w={} h={}", x, y, width, height);
        return annotation;
    }

    private int post(String url, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            logger.error("POST {} -> {} : {}", url, resp.statusCode(), resp.body());
        }
        return resp.statusCode();
    }

    private int put(String url, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> resp = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            logger.error("PUT {} -> {} : {}", url, resp.statusCode(), resp.body());
        }
        return resp.statusCode();
    }
}
