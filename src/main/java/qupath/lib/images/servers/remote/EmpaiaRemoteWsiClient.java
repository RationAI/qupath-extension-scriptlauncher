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
import java.util.List;
import java.util.Map;


/**
 * Minimal EMPAIA HTTP client for fetching WSI metadata and tiles.
 *
 * This implementation uses Jackson for JSON parsing and probes exactly
 * the `/inputs/my_wsi` path for autodiscovery as requested.
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
     * Autodiscovery now uses fetchMetadata("/inputs/my_wsi") directly; getWsiID()
     * was removed to centralize id handling inside Metadata.
     */


    public static class Metadata {
        public int width = 0;
        public int height = 0;
        public String id = null;
        public List<int[]> levels = new ArrayList<>(); // each entry [w,h]
        public int tileWidth = 256;
        public int tileHeight = 256;
    }

    /**
     * Fetch metadata from /{baseApi}/{job}/pixelmaps/{wsi}/info and fall back
     * to /{baseApi}/{job}/inputs/input_wsi when needed.
    */
                 // (e.g. { "my_wsi": { "id": "..." } }) — handled by
    public Metadata fetchMetadata() throws IOException, InterruptedException {
        final String url = String.format("%s/%s/inputs/my_wsi", baseApi, jobId);
        logger.info("Fetching metadata from {} (using inputs/my_wsi)", url);

        HttpRequest req = newRequestBuilder(url).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Failed to fetch metadata from inputs/my_wsi: " + resp.statusCode());
        }

        final String body = resp.body();
        final Metadata md = new Metadata();

        try {
            final JsonNode wsiNode = mapper.readTree(body);

            // Populate metadata id if available in the WSI node
            if (wsiNode.has("id") && wsiNode.get("id").isTextual()) {
                md.id = wsiNode.get("id").asText();
            }

            // According to the agreed shape, extent contains x and y
            if (wsiNode.has("extent") && wsiNode.get("extent").isObject()) {
                final JsonNode extent = wsiNode.get("extent");
                md.width = extent.has("x") ? extent.get("x").asInt(md.width) : md.width;
                md.height = extent.has("y") ? extent.get("y").asInt(md.height) : md.height;
            }

            // Levels: each level contains an extent with x/y
            if (wsiNode.has("levels") && wsiNode.get("levels").isArray()) {
                for (JsonNode lvl : wsiNode.get("levels")) {
                    if (lvl.has("extent") && lvl.get("extent").isObject()) {
                        final JsonNode e = lvl.get("extent");
                        int w = e.has("x") ? e.get("x").asInt(0) : 0;
                        int h = e.has("y") ? e.get("y").asInt(0) : 0;
                        if (w > 0 && h > 0) md.levels.add(new int[]{w, h});
                    }
                }
            }

            // tile_extent is an object with x/y
            if (wsiNode.has("tile_extent") && wsiNode.get("tile_extent").isObject()) {
                final JsonNode te = wsiNode.get("tile_extent");
                md.tileWidth = te.has("x") ? te.get("x").asInt(md.tileWidth) : md.tileWidth;
                md.tileHeight = te.has("y") ? te.get("y").asInt(md.tileHeight) : md.tileHeight;
            }

        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            logger.debug("Failed to parse metadata JSON with Jackson: {}", e.getMessage());
            // If parsing fails, keep defaults; the structure assumption above is strict
        }

        if (md.levels.isEmpty() && md.width > 0 && md.height > 0) {
            md.levels.add(new int[]{md.width, md.height});
        }

        return md;
    }

    /**
     * Build a GET request with common headers applied.
     */
    private HttpRequest.Builder newRequestBuilder(String url) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        headers.forEach(builder::header);
        return builder;
    }

    /**
     * Fetch a tile image as BufferedImage using tiles endpoint.
     * GET /{job_id}/tiles/{wsi_id}/level/{level}/position/{tile_x}/{tile_y}
     */
    public BufferedImage fetchTile(String wsiId, int level, int tileX, int tileY) throws IOException, InterruptedException {
        final String url = String.format("%s/%s/tiles/%s/level/%d/position/%d/%d", baseApi, jobId, wsiId, level, tileX, tileY);
        logger.debug("Fetching tile from {}", url);

        final HttpRequest request = newRequestBuilder(url).build();
        final HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Tile request failed: " + resp.statusCode());
        }

        final byte[] data = resp.body();
        try (final ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            final BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("Failed to decode tile image");
            return img;
        }
    }

    /**
     * Fetch an arbitrary region from the WSI using the regions endpoint.
     * GET /{job_id}/regions/{wsi_id}/level/{level}/start/{start_x}/{start_y}/size/{size_x}/{size_y}
     */
    public BufferedImage fetchRegion(String wsiId, int level, int startX, int startY, int sizeX, int sizeY) throws IOException, InterruptedException {
        final String url = String.format("%s/%s/regions/%s/level/%d/start/%d/%d/size/%d/%d", baseApi, jobId, wsiId, level, startX, startY, sizeX, sizeY);
        logger.debug("Fetching region from {}", url);

        final HttpRequest request = newRequestBuilder(url).build();
        final HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Region request failed: " + resp.statusCode());
        }

        final byte[] data = resp.body();
        try (final ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            final BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("Failed to decode region image");
            return img;
        }
    }
}
