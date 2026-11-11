import org.bytedeco.opencv.global.opencv_core
import org.bytedeco.opencv.global.opencv_dnn
import org.bytedeco.opencv.opencv_core.Mat
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.ROIs
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths

// Estimates the PD-L1 Tumor Proportion Score (TPS) inside the input ROI:
//   TPS = PD-L1 positive tumor cells / all tumor cells x 100
// Cells are detected and classified by the IGNITE PD-L1 model (YOLOv5m, Radboud UMC, Zenodo 17735903),
// exported to ONNX and run with QuPath's bundled OpenCV DNN. The model was trained on NSCLC PD-L1 IHC
// (clones 22C3, SP263, E1L3N); its weights are CC BY-NC-SA 4.0. The result is an estimate, not a clinical TPS.
// Pre- and post-processing follow IGNITE's code/pdl1_detection: RGB scaled to 0-1, 512 px tiles at
// 0.5 um/px without overlap, class-agnostic NMS, one detection per cell at the box center.
// Injected by the Java script runner: api, imageData, hierarchy, server, selectionModel, project, args

// Thrown errors make the launcher fail the job. Calling api.failJob() and returning would still finalize it.
def MODELS_DIR = "/scripts/models"
def modelPath = "${MODELS_DIR}/ignite/pdl1_ignite_yolov5m.onnx"
int TILE = 512
double MODEL_PIXEL_SIZE = 0.5   // um/px the model was trained at
int NUM_CLASSES = 3             // 0 = PD-L1 negative tumor, 1 = PD-L1 positive tumor, 2 = non-tumor
int ROW = 5 + NUM_CLASSES       // cx, cy, w, h, objectness, class scores
int MAX_DET = 1500              // per tile, as in IGNITE
int MAX_TILES = 1000            // about 12 minutes on CPU
double CELL_RADIUS_UM = 4.0     // size of the circle drawn for each cell

api.reportProgress(0.0)
def inputRoi = api.getInputRoi()
if (inputRoi == null)
    throw new IllegalArgumentException("input_roi is required: draw a box inside the tumor region")
def roi = inputRoi.getROI()
println "Using input_roi: ${roi}"

def confThreshold = (api.getInput("conf_threshold") ?: "0.5").toDouble()
def iouThreshold  = (api.getInput("iou_threshold")  ?: "0.6").toDouble()
if (!(confThreshold > 0 && confThreshold < 1))
    throw new IllegalArgumentException("conf_threshold must be between 0 and 1")
if (!(iouThreshold > 0 && iouThreshold < 1))
    throw new IllegalArgumentException("iou_threshold must be between 0 and 1")

double pixelSize = server.getPixelCalibration().getAveragedPixelSizeMicrons()
if (Double.isNaN(pixelSize) || pixelSize <= 0)
    throw new IllegalStateException("The slide has no pixel size; the model needs it to work at 0.5 um/px")
double downsample = MODEL_PIXEL_SIZE / pixelSize

if (!Files.exists(Paths.get(modelPath)))
    throw new IllegalStateException("Model path does not exist: ${modelPath}")

// Tiles cover the ROI bounds on a grid of 512 model pixels. They read real slide pixels beyond the ROI
// so cells at the ROI edge keep their context; detections outside the ROI are dropped afterwards.
double step = TILE * downsample
int x0 = (int) Math.floor(roi.getBoundsX())
int y0 = (int) Math.floor(roi.getBoundsY())
int x1 = Math.min((int) Math.ceil(roi.getBoundsX() + roi.getBoundsWidth()), server.getWidth())
int y1 = Math.min((int) Math.ceil(roi.getBoundsY() + roi.getBoundsHeight()), server.getHeight())
int tilesX = (int) Math.ceil((x1 - x0) / step)
int tilesY = (int) Math.ceil((y1 - y0) / step)
int totalTiles = tilesX * tilesY
if (totalTiles <= 0)
    throw new IllegalArgumentException("input_roi is empty or outside the slide")
if (totalTiles > MAX_TILES)
    throw new IllegalArgumentException("input_roi is too large (${totalTiles} tiles, limit ${MAX_TILES}): draw a smaller box")

println "PD-L1 params: confThreshold=${confThreshold} iouThreshold=${iouThreshold} pixelSize=${pixelSize} downsample=${downsample} tiles=${tilesX}x${tilesY}"

def net = opencv_dnn.readNetFromONNX(modelPath)

// Packs a tile into a [1, 3, 512, 512] RGB float blob scaled to 0-1. Pixels beyond the slide edge stay white.
def toBlob = { BufferedImage img ->
    int plane = TILE * TILE
    float[] blob = new float[3 * plane]
    Arrays.fill(blob, 1.0f)
    int w = Math.min(img.getWidth(), TILE)
    int h = Math.min(img.getHeight(), TILE)
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int rgb = img.getRGB(x, y)
            int i = y * TILE + x
            blob[i]             = ((rgb >> 16) & 0xff) / 255.0f
            blob[plane + i]     = ((rgb >> 8) & 0xff) / 255.0f
            blob[2 * plane + i] = (rgb & 0xff) / 255.0f
        }
    }
    return blob
}

// Same IoU as torchvision.ops.box_iou, which YOLOv5's NMS uses.
def iou = { double[] a, double[] b ->
    double iw = Math.min(a[2], b[2]) - Math.max(a[0], b[0])
    double ih = Math.min(a[3], b[3]) - Math.max(a[1], b[1])
    if (iw <= 0 || ih <= 0) return 0.0d
    double inter = iw * ih
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
}

// YOLOv5 non_max_suppression with agnostic=True: keep rows with objectness and objectness x class
// score above the threshold, then greedily drop boxes overlapping a higher-scoring box by more than iouThreshold.
// Returns [x1, y1, x2, y2, score, class] per kept box, in tile pixels.
def decode = { float[] out ->
    List<double[]> candidates = []
    int rows = out.length.intdiv(ROW)
    for (int r = 0; r < rows; r++) {
        int o = r * ROW
        float objectness = out[o + 4]
        if (objectness <= confThreshold) continue
        int best = 0
        for (int c = 1; c < NUM_CLASSES; c++)
            if (out[o + 5 + c] > out[o + 5 + best]) best = c
        double score = objectness * out[o + 5 + best]
        if (score <= confThreshold) continue
        double cx = out[o], cy = out[o + 1], hw = out[o + 2] / 2, hh = out[o + 3] / 2
        candidates << ([cx - hw, cy - hh, cx + hw, cy + hh, score, best] as double[])
    }
    candidates.sort { a, b -> Double.compare(b[4], a[4]) }
    List<double[]> kept = []
    for (double[] box : candidates) {
        if (kept.size() >= MAX_DET) break
        if (kept.every { iou(it, box) <= iouThreshold }) kept << box
    }
    return kept
}

def pathClasses = [
    PathClass.fromString("PD-L1 negative tumor"),
    PathClass.fromString("PD-L1 positive tumor"),
    PathClass.fromString("Non-tumor"),
]
def cellsByClass = [[], [], []]
double radius = CELL_RADIUS_UM / pixelSize

api.reportProgress(0.05)
println "Running PD-L1 cell detection on ${totalTiles} tiles..."
int done = 0
for (int ty = 0; ty < tilesY; ty++) {
    for (int tx = 0; tx < tilesX; tx++) {
        int rx = (int) Math.round(x0 + tx * step)
        int ry = (int) Math.round(y0 + ty * step)
        int rw = (int) Math.min(Math.round(step), server.getWidth() - rx)
        int rh = (int) Math.min(Math.round(step), server.getHeight() - ry)
        def request = RegionRequest.createInstance(server.getPath(), downsample, rx, ry, rw, rh)
        BufferedImage img = server.readRegion(request)

        Mat input = new Mat(4, [1, 3, TILE, TILE] as int[], opencv_core.CV_32F)
        Mat output = null
        float[] out
        try {
            input.createBuffer().put(toBlob(img))
            net.setInput(input)
            output = net.forward()
            def buffer = output.createBuffer()
            out = new float[buffer.remaining()]
            buffer.get(out)
        } finally {
            input.close()
            output?.close()
        }

        double scaleX = rw / (double) img.getWidth()
        double scaleY = rh / (double) img.getHeight()
        for (double[] box : decode(out)) {
            double cx = (box[0] + box[2]) / 2
            double cy = (box[1] + box[3]) / 2
            if (cx >= img.getWidth() || cy >= img.getHeight()) continue  // in the padding beyond the slide
            double gx = rx + cx * scaleX
            double gy = ry + cy * scaleY
            if (!roi.contains(gx, gy)) continue
            def circle = ROIs.createEllipseROI(gx - radius, gy - radius, 2 * radius, 2 * radius, roi.getImagePlane())
            int cls = (int) box[5]
            cellsByClass[cls] << PathObjects.createDetectionObject(circle, pathClasses[cls])
        }

        done++
        api.reportProgress(0.05 + 0.8 * done / totalTiles)
    }
}

def negative = cellsByClass[0]
def positive = cellsByClass[1]
def nonTumor = cellsByClass[2]
int tumorCount = positive.size() + negative.size()
println "Detected ${positive.size()} PD-L1 positive tumor, ${negative.size()} PD-L1 negative tumor and ${nonTumor.size()} non-tumor cells"
if (tumorCount == 0)
    throw new IllegalStateException("No tumor cells detected in input_roi")

def score = 100.0d * positive.size() / tumorCount
println "PD-L1 TPS estimate: ${score}% (${positive.size()} positive of ${tumorCount} tumor cells)"
// Clinical TPS scoring requires at least 100 viable tumor cells
if (tumorCount < 100)
    println "WARNING: fewer than 100 tumor cells, too few for a reliable score"

api.postAnnotations("positive_tumor_cells", positive)
api.postAnnotations("negative_tumor_cells", negative)
api.postAnnotations("non_tumor_cells", nonTumor)
api.reportProgress(0.9)
api.postValues("score_percent", [score])
api.postValues("positive_count", [positive.size()])
api.postValues("tumor_count", [tumorCount])
api.postValues("non_tumor_count", [nonTumor.size()])
api.reportProgress(1.0)
