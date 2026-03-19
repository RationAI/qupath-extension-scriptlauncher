package qupath.lib.images.servers.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.servers.AbstractTileableImageServer;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.ImageServerMetadata.ImageResolutionLevel;
import qupath.lib.images.servers.PixelType;
import qupath.lib.images.servers.TileRequest;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Single, clean implementation of an EMPAIA-backed ImageServer.
 *
 * Behavior:
 * - Build QuPath ImageServerMetadata from EMPAIA metadata
 * - readTile: try regions endpoint for exact rectangle; fall back to tiles endpoint
 * - Flatten returned images to TYPE_INT_RGB
 */
public class EmpaiaRemoteWsiImageServer extends AbstractTileableImageServer {

	private static final Logger logger = LoggerFactory.getLogger(EmpaiaRemoteWsiImageServer.class);

	private final URI uri;
	private final EmpaiaRemoteWsiClient client;
	private final String wsiId;

	private ImageServerMetadata originalMetadata;

	/** Raw metadata returned by the EMPAIA client (kept for level math). */
	private EmpaiaRemoteWsiClient.Metadata metadata;

	/**
	 * Construct an image server backed by an existing EMPAIA client and WSI id.
	 */
	public EmpaiaRemoteWsiImageServer(EmpaiaRemoteWsiClient client, String wsiId) throws IOException {
		super();
		this.uri = null;
		this.client = client;
		this.wsiId = wsiId;
		initFromMetadata();
	}

	/**
	 * Construct an image server from a {@link URI}. The constructor will
	 * create an {@link EmpaiaRemoteWsiClient} using environment variables
	 * EMPAIA_BASE_API and EMPAIA_JOB_ID. The WSI id is taken from the URI
	 * path if present, otherwise the URI string is used as a fallback.
	 */
	public EmpaiaRemoteWsiImageServer(URI uri, String... args) throws IOException {
		super();
		this.uri = uri;

		String baseApi = System.getenv("EMPAIA_BASE_API");
		String jobId = System.getenv("EMPAIA_JOB_ID");
		String token = System.getenv("EMPAIA_TOKEN");

		if (baseApi == null || jobId == null) {
			throw new IOException("EMPAIA_BASE_API and EMPAIA_JOB_ID environment variables must be set");
		}

		Map<String, String> headers = token != null ? Map.of("Authorization", "Bearer " + token) : Collections.emptyMap();
		this.client = new EmpaiaRemoteWsiClient(baseApi, jobId, headers);

		String path = uri.getPath();
		String id = null;
		if (path != null && !path.isBlank()) {
			Path p = Path.of(path);
			id = p.getFileName() != null ? p.getFileName().toString() : null;
		}

		// Use the path filename if available, otherwise fall back to the URI text.
		this.wsiId = (id != null) ? id : (uri != null ? uri.toString() : null);

		initFromMetadata();
	}

	private void initFromMetadata() throws IOException {
		if (wsiId == null || wsiId.isBlank()) {
			throw new IOException("Missing WSI identifier for EMPAIA image server");
		}

		EmpaiaRemoteWsiClient.Metadata md;
		try {
			md = client.fetchMetadata();
		} catch (Exception e) {
			throw new IOException("Failed to fetch EMPAIA metadata for wsiId=" + wsiId, e);
		}

		if (md == null) {
			throw new IOException("EMP AIA metadata was empty for wsiId=" + wsiId);
		}

		// store for later use in readTile
		this.metadata = md;

		ImageResolutionLevel.Builder builder = new ImageResolutionLevel.Builder(Math.max(1, md.width), Math.max(1, md.height));
		if (md.levels != null) {
			for (int[] wh : md.levels) {
				if (wh != null && wh.length >= 2) {
					builder.addLevel(wh[0], wh[1]);
				}
			}
		}
		List<ImageResolutionLevel> levels = builder.build();

		originalMetadata = new ImageServerMetadata.Builder(getClass(), (uri != null ? uri.toString() : wsiId), md.width, md.height)
				.channels(ImageChannel.getDefaultRGBChannels())
				.rgb(true)
				.pixelType(PixelType.UINT8)
				.preferredTileSize(md.tileWidth, md.tileHeight)
				.levels(levels)
				.build();

		// Best-effort fetch to log server capability
		try {
			BufferedImage img = client.fetchTile(wsiId, 0, 0, 0);
			if (img != null) {
				logger.debug("Test tile fetched: {}x{}", img.getWidth(), img.getHeight());
			}
		} catch (Exception e) {
			logger.debug("Could not fetch test tile: {}", e.getMessage());
		}
	}

	@Override
	public Collection<URI> getURIs() {
		if (uri != null) {
			return Collections.singletonList(uri);
		}

		// Provide a stable, well-formed URI using a custom scheme so callers
		// that parse URIs don't encounter "Missing scheme" errors when a
		// plain id string is used as the identifier.
		if (wsiId != null) {
			return Collections.singletonList(URI.create("empaia:" + wsiId));
		}
		return Collections.emptyList();
	}

	@Override
	protected String createID() {
		return uri != null ? uri.toString() : ("empaia:" + wsiId);
	}

	@Override
	public void close() {
		// No resources to free here
	}

	@Override
	public String getServerType() {
		return "EmpaiaRemoteWSI";
	}

	@Override
	public BufferedImage readTile(TileRequest tileRequest) throws IOException {
		final int imageX = tileRequest.getImageX();
		final int imageY = tileRequest.getImageY();
		final int tileW = tileRequest.getTileWidth();
		final int tileH = tileRequest.getTileHeight();
		final int level = tileRequest.getLevel();

		try {
			// Convert coordinates to the requested level's coordinate space.
			int imageXLevel = imageX;
			int imageYLevel = imageY;
			if (metadata != null && metadata.width > 0 && level >= 0 && level < metadata.levels.size()) {
				int levelWidth = metadata.levels.get(level)[0];
				int levelHeight = metadata.levels.get(level)[1];
				// Map base-level pixel coords to this level
				imageXLevel = (int) Math.floor(imageX * ((double) levelWidth / Math.max(1, metadata.width)));
				imageYLevel = (int) Math.floor(imageY * ((double) levelHeight / Math.max(1, metadata.height)));
			}

			// Try the precise regions endpoint first (using level-specific coords)
			try {
				BufferedImage region = client.fetchRegion(wsiId, level, imageXLevel, imageYLevel, tileW, tileH);
				if (region == null) {
					throw new IOException("Server returned empty region");
				}

				if (region.getWidth() != tileW || region.getHeight() != tileH) {
					BufferedImage out = new BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_RGB);
					Graphics2D g = out.createGraphics();
					logger.info("using region size {}x{} for tile {}x{} at level {} (requested {}x{})",
							region.getWidth(), region.getHeight(), imageXLevel, imageYLevel, level, tileW, tileH);
					try {
						g.drawImage(region, 0, 0, null);
					} finally {
						g.dispose();
					}
					return out;
				}

				if (region.getType() != BufferedImage.TYPE_INT_RGB) {
					BufferedImage rgb = new BufferedImage(region.getWidth(), region.getHeight(), BufferedImage.TYPE_INT_RGB);
					Graphics2D g = rgb.createGraphics();
					logger.info("converting region image type {} to TYPE_INT_RGB for tile {}x{} at level {}",
							region.getType(), imageXLevel, imageYLevel, level);
					try {
						g.drawImage(region, 0, 0, null);
					} finally {
						g.dispose();
					}
					return rgb;
				}

				return region;
			} catch (IOException e) {
				logger.debug("Region endpoint failed for wsiId={}, level={}, x={}, y={} — falling back to tiles: {}",
						wsiId, level, imageXLevel, imageYLevel, e.getMessage());

				final int serverTileW = originalMetadata.getPreferredTileWidth();
				final int serverTileH = originalMetadata.getPreferredTileHeight();
				final int tileXIndex = Math.floorDiv(imageXLevel, serverTileW);
				final int tileYIndex = Math.floorDiv(imageYLevel, serverTileH);
				final int offsetX = imageXLevel - tileXIndex * serverTileW;
				final int offsetY = imageYLevel - tileYIndex * serverTileH;

				BufferedImage serverTile = client.fetchTile(wsiId, level, tileXIndex, tileYIndex);
				if (serverTile == null) {
					throw new IOException("Server returned empty tile");
				}

				BufferedImage out = new BufferedImage(tileW, tileH, BufferedImage.TYPE_INT_RGB);
				Graphics2D g = out.createGraphics();
				try {
					g.drawImage(serverTile, -offsetX, -offsetY, null);
				} finally {
					g.dispose();
				}
				return out;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while fetching tile", e);
		} catch (Exception e) {
			// Log detailed context to help debugging remote failures
			logger.error("Failed to read tile for wsiId={}, level={}, x={}, y={}, w={}, h={}",
					wsiId, level, imageX, imageY, tileW, tileH, e);
			throw new IOException("Failed to read tile: " + e.getMessage(), e);
		}
	}

	@Override
	public List<String> getAssociatedImageList() {
		return Collections.emptyList();
	}

	/**
	 * Builder creation is not supported by this server implementation.
	 */
	@Override
	protected qupath.lib.images.servers.ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
		throw new UnsupportedOperationException("ImageServer builder not supported for EmpaiaRemoteWsiImageServer");
	}

	@Override
	public BufferedImage getAssociatedImage(String name) {
		throw new IllegalArgumentException("No associated images available: " + name);
	}

	@Override
	public ImageServerMetadata getOriginalMetadata() {
		return originalMetadata;
	}

}

