package qupath.lib.images.servers.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal EMPAIA HTTP client for fetching WSI metadata and tiles.
 *
 * This implementation uses Jackson for JSON parsing and probes exactly
 * the `/inputs/input_wsi` path for autodiscovery as requested.
 */
public class EmpaiaRemoteWsiClient {

    private static final Logger logger = LoggerFactory.getLogger(EmpaiaRemoteWsiClient.class);

    private final HttpClient client;
    private final String baseApi; // e.g. https://host:port/v3/app (contains /v3)
    private final String jobId;
    private final Map<String, String> headers;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmpaiaRemoteWsiClient(String baseApi, String jobId, Map<String, String> headers) {
        this.client = HttpClient.newHttpClient();
        // Normalize baseApi: strip trailing slashes
        this.baseApi = (baseApi == null) ? "" : baseApi.replaceAll("/+$", "");
        this.jobId = jobId;
        this.headers = (headers == null) ? Collections.emptyMap() : headers;
    }

    /**
     * Probe /{baseApi}/{jobId}/inputs/input_wsi and return one or more
     * pixelmap / wsi ids discovered there.
     */
    public List<String> listPixelmaps() throws IOException, InterruptedException {
    String url = String.format("%s/%s/inputs/my_wsi", baseApi, jobId);
        logger.debug("Probing inputs for pixelmaps (input_wsi): {}", url);

        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        headers.forEach(b::header);
        HttpRequest req = b.build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Failed to probe input_wsi: " + status + " " + url);
        }
        String body = resp.body();

        // Try Jackson parsing first
        try {
            JsonNode root = mapper.readTree(body);
            // If the response is an object and has an id field
            if (root.isObject()) {
                if (root.has("id") && root.get("id").isTextual()) {
                    return List.of(root.get("id").asText());
                }
                // Some deployments return the WSI entry directly under a key
                // e.g. { "input_wsi": { "id": "..." } }
                for (String key : new String[]{"input_wsi", "wsi", "pixelmap", "pixelmap_input"}) {
                    if (root.has(key)) {
                        JsonNode n = root.get(key);
                        if (n.isTextual()) return List.of(n.asText());
                        if (n.isObject() && n.has("id") && n.get("id").isTextual())
                            return List.of(n.get("id").asText());
                    }
                }
            }

            // If response is an array of entries
            if (root.isArray()) {
                Set<String> ids = new LinkedHashSet<>();
                for (JsonNode el : root) {
                    if (el.isTextual()) ids.add(el.asText());
                    else if (el.isObject()) {
                        if (el.has("id") && el.get("id").isTextual()) ids.add(el.get("id").asText());
                        else if (el.has("wsi") && el.get("wsi").isTextual()) ids.add(el.get("wsi").asText());
                    }
                }
                if (!ids.isEmpty()) return new ArrayList<>(ids);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse JSON from input_wsi with Jackson: {}", e.getMessage());
            // Fall through to permissive extraction
        }

        // Permissive fallback: extract quoted strings or id-like tokens
        List<String> ids = extractIdsFromBody(body);
        if (!ids.isEmpty()) return ids;

        throw new IOException("No pixelmaps found in input_wsi for job " + jobId);
    }

    // Extract id-like strings from a JSON text body using patterns.
    // This is intentionally permissive: it looks for arrays of strings,
    // fields named like id/uuid/pixelmap_id, and finally any UUID-like token.
    private List<String> extractIdsFromBody(String body) {
        List<String> ids = new ArrayList<>();
        if (body == null || body.isBlank()) return ids;

        Set<String> seen = new LinkedHashSet<>();
        String trimmed = body.trim();

        // 1) If the body looks like a JSON array (e.g. ["id1","id2"]) collect quoted strings.
        if (trimmed.startsWith("[")) {
            Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(body);
            while (m.find()) seen.add(m.group(1));
        }

        // 2) Find explicit id-like fields: "pixelmap_id" : "..." etc.
        Pattern idField = Pattern.compile("\"(pixelmap_id|wsi_id|slide_id|id|uuid|name)\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
        Matcher mi = idField.matcher(body);
        while (mi.find()) seen.add(mi.group(2));

        // 3) Fallback: find UUID-like tokens in the body (common id format)
        Pattern uuidPat = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        Matcher mu = uuidPat.matcher(body);
        while (mu.find()) seen.add(mu.group());

        ids.addAll(seen);
        return ids;
    }

    public static class Metadata {
        public int width = 0;
        public int height = 0;
        public List<int[]> levels = new ArrayList<>(); // each entry [w,h]
        public int tileWidth = 256;
        public int tileHeight = 256;
    }

    /**
     * Fetch metadata from /{baseApi}/{job}/pixelmaps/{wsi}/info and fall back
     * to /{baseApi}/{job}/inputs/input_wsi when needed.
     */
    public Metadata fetchMetadata(String wsiId) throws IOException, InterruptedException {
        String primary = String.format("%s/%s/pixelmaps/%s/info", baseApi, jobId, wsiId);
        logger.debug("Fetching metadata from {}", primary);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(primary)).GET();
        headers.forEach(b::header);
        HttpRequest req = b.build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            logger.debug("Primary metadata endpoint returned {}. Trying inputs/my_wsi fallback.", resp.statusCode());
            String alt = String.format("%s/%s/inputs/my_wsi", baseApi, jobId);
            HttpRequest.Builder b2 = HttpRequest.newBuilder().uri(URI.create(alt)).GET();
            headers.forEach(b2::header);
            HttpRequest req2 = b2.build();
            HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
            if (resp2.statusCode() < 200 || resp2.statusCode() >= 300) {
                throw new IOException("Failed to fetch metadata: primary=" + resp.statusCode() + " fallback=" + resp2.statusCode());
            }
            resp = resp2;
        }

        String body = resp.body();
        Metadata md = new Metadata();

        try {
            JsonNode root = mapper.readTree(body);

            // Many EMPAIA responses place extent as { x:..., y:... }
            if (root.has("extent") && root.get("extent").isObject()) {
                JsonNode extent = root.get("extent");
                if (extent.has("width")) {
                    md.width = extent.get("width").asInt(md.width);
                } else if (extent.has("x")) {
                    md.width = extent.get("x").asInt(md.width);
                }
                if (extent.has("height")) {
                    md.height = extent.get("height").asInt(md.height);
                } else if (extent.has("y")) {
                    md.height = extent.get("y").asInt(md.height);
                }
            }

            // Some payloads expose width/height at root (either width/height or x/y)
            if (md.width == 0) {
                if (root.has("width")) md.width = root.get("width").asInt(md.width);
                else if (root.has("x")) md.width = root.get("x").asInt(md.width);
            }
            if (md.height == 0) {
                if (root.has("height")) md.height = root.get("height").asInt(md.height);
                else if (root.has("y")) md.height = root.get("y").asInt(md.height);
            }

            // Levels: support both simple arrays and objects with extent {x,y} or {width,height}
            if (root.has("levels") && root.get("levels").isArray()) {
                for (JsonNode lvl : root.get("levels")) {
                    int w = 0, h = 0;
                    if (lvl.has("extent") && lvl.get("extent").isObject()) {
                        JsonNode e = lvl.get("extent");
                        if (e.has("width")) w = e.get("width").asInt(0);
                        else if (e.has("x")) w = e.get("x").asInt(0);
                        if (e.has("height")) h = e.get("height").asInt(0);
                        else if (e.has("y")) h = e.get("y").asInt(0);
                    } else if (lvl.has("width") && lvl.has("height")) {
                        w = lvl.get("width").asInt(0);
                        h = lvl.get("height").asInt(0);
                    } else if (lvl.isArray() && lvl.size() >= 2) {
                        w = lvl.get(0).asInt(0);
                        h = lvl.get(1).asInt(0);
                    }
                    if (w > 0 && h > 0) md.levels.add(new int[]{w, h});
                }
            }

            // tile_extent may be object with x/y or width/height, or an array
            if (root.has("tile_extent")) {
                JsonNode te = root.get("tile_extent");
                if (te.isArray() && te.size() >= 2) {
                    md.tileWidth = te.get(0).asInt(md.tileWidth);
                    md.tileHeight = te.get(1).asInt(md.tileHeight);
                } else if (te.isObject()) {
                    if (te.has("width")) md.tileWidth = te.get("width").asInt(md.tileWidth);
                    else if (te.has("x")) md.tileWidth = te.get("x").asInt(md.tileWidth);
                    if (te.has("height")) md.tileHeight = te.get("height").asInt(md.tileHeight);
                    else if (te.has("y")) md.tileHeight = te.get("y").asInt(md.tileHeight);
                }
            }

            // tile size may also be under tileWidth/tileHeight or tile_size
            if (root.has("tileWidth")) md.tileWidth = root.get("tileWidth").asInt(md.tileWidth);
            if (root.has("tileHeight")) md.tileHeight = root.get("tileHeight").asInt(md.tileHeight);

            // Some payloads include num_levels or channel_depth which are not directly used

        } catch (Exception e) {
            logger.debug("Failed to parse metadata JSON with Jackson: {}", e.getMessage());
            // best-effort fallback to regex
            md.width = Math.max(md.width, extractFirstInt(body, "\"width\"\\s*:\\s*(\\d+)"));
            md.height = Math.max(md.height, extractFirstInt(body, "\"height\"\\s*:\\s*(\\d+)"));
        }

        if (md.levels.isEmpty() && md.width > 0 && md.height > 0) {
            md.levels.add(new int[]{md.width, md.height});
        }

        return md;
    }

    private int extractFirstInt(String body, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(body);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception e) { return -1; }
        }
        return -1;
    }

    /**
     * Fetch a tile image as BufferedImage using tiles endpoint.
     * GET /{job_id}/tiles/{wsi_id}/level/{level}/position/{tile_x}/{tile_y}
     */
    public BufferedImage fetchTile(String wsiId, int level, int tileX, int tileY) throws IOException, InterruptedException {
        String url = String.format("%s/%s/tiles/%s/level/%d/position/%d/%d", baseApi, jobId, wsiId, level, tileX, tileY);
        logger.debug("Fetching tile from {}", url);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        headers.forEach(b::header);
        HttpRequest req = b.build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Tile request failed: " + resp.statusCode());
        }
        byte[] data = resp.body();
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("Failed to decode tile image");
            return img;
        }
    }

    /**
     * Fetch an arbitrary region from the WSI using the regions endpoint.
     * GET /{job_id}/regions/{wsi_id}/level/{level}/start/{start_x}/{start_y}/size/{size_x}/{size_y}
     */
    public BufferedImage fetchRegion(String wsiId, int level, int startX, int startY, int sizeX, int sizeY) throws IOException, InterruptedException {
        String url = String.format("%s/%s/regions/%s/level/%d/start/%d/%d/size/%d/%d", baseApi, jobId, wsiId, level, startX, startY, sizeX, sizeY);
        logger.debug("Fetching region from {}", url);
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        headers.forEach(b::header);
        HttpRequest req = b.build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Region request failed: " + resp.statusCode());
        }
        byte[] data = resp.body();
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("Failed to decode region image");
            return img;
        }
    }
}
