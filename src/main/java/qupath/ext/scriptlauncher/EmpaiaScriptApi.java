package qupath.ext.scriptlauncher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();

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

            List<Map<String, Object>> items = new ArrayList<>();
            for (Number v : values) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", "result");
                item.put("type", "float");
                item.put("value", v.doubleValue());
                item.put("creator_id", jobId);
                item.put("creator_type", "job");
                items.add(item);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "collection");
            body.put("creator_id", jobId);
            body.put("creator_type", "job");
            body.put("item_type", "float");
            body.put("reference_id", wsiId);
            body.put("reference_type", "wsi");
            body.put("items", items);

            int status = post(outputUrl, toJson(body));
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

            List<Map<String, Object>> items = new ArrayList<>();
            int count = 0;
            for (PathObject detection : detections) {
                ROI roi = detection.getROI();
                if (roi == null || roi.getAllPoints().isEmpty()) continue;

                var points = roi.getAllPoints();
                List<List<Integer>> coordinates = new ArrayList<>();
                for (int i = 0; i < points.size(); i++) {
                    var p = points.get(i);
                    coordinates.add(List.of((int)Math.round(p.getX()), (int)Math.round(p.getY())));
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", "detection_" + count);
                item.put("type", "polygon");
                item.put("creator_id", jobId);
                item.put("creator_type", "job");
                item.put("reference_id", wsiId);
                item.put("reference_type", "wsi");
                item.put("npp_created", 1000);
                item.put("coordinates", coordinates);
                items.add(item);
                count++;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "collection");
            body.put("creator_id", jobId);
            body.put("creator_type", "job");
            body.put("item_type", "polygon");
            body.put("reference_id", wsiId);
            body.put("reference_type", "wsi");
            body.put("items", items);

            int status = post(outputUrl, toJson(body));
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
            String body = toJson(Map.of("user_message", message));
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
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode upperLeft = root.path("upper_left");
            double x = upperLeft.path(0).asDouble();
            double y = upperLeft.path(1).asDouble();
            double width = root.path("width").asDouble();
            double height = root.path("height").asDouble();

            ROI roi = ROIs.createRectangleROI(x, y, width, height, ImagePlane.getDefaultPlane());
            PathObject annotation = PathObjects.createAnnotationObject(roi);
            annotation.setName("input_roi");
            logger.info("Parsed input_roi: x={} y={} w={} h={}", x, y, width, height);
            return annotation;
        } catch (Exception e) {
            logger.warn("Failed to parse input_roi JSON", e);
            return null;
        }
    }

    private String toJson(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
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
