import qupath.ext.stardist.StarDist2D
import java.nio.file.Files
import java.nio.file.Paths

// Injected by the Java script runner: api, imageData, hierarchy, server, selectionModel, project, args

api.reportProgress(0.0)
def inputRoi = api.getInputRoi()
if (inputRoi == null)
    println "No input_roi provided, running StarDist on full image"
else
    println "Using input_roi: ${inputRoi.getROI()}"

def modelPath = "/scripts/models/stardist/he_heavy_augment.pb"

if (!Files.exists(Paths.get(modelPath))) {
    api.failJob("Model path does not exist: ${modelPath}")
    return
}

println "Using StarDist model: ${modelPath}"

api.reportProgress(0.1)

def threshold     = (api.getInput("threshold")      ?: "0.5").toDouble()
def pixelSize     = (api.getInput("pixel_size")     ?: "0.5").toDouble()
def tileSize      = (api.getInput("tile_size")      ?: "1024").toInteger()
def cellExpansion = (api.getInput("cell_expansion") ?: "0.0").toDouble()

println "StarDist params: threshold=${threshold} pixelSize=${pixelSize} tileSize=${tileSize} cellExpansion=${cellExpansion}"

def stardistBuilder = StarDist2D.builder(modelPath)
    .normalizePercentiles(1, 99)
    .threshold(threshold)
    .pixelSize(pixelSize)
    .tileSize(tileSize)
if (cellExpansion > 0) {
    stardistBuilder = stardistBuilder.cellExpansion(cellExpansion)
}
def stardist = stardistBuilder.build()

println "Running StarDist detection..."
def parents = inputRoi != null ? [inputRoi] : [hierarchy.getRootObject()]
stardist.detectObjects(imageData, parents)

api.reportProgress(0.8)

def detections = hierarchy.getDetectionObjects()
println "Detected ${detections.size()} objects"

api.postAnnotations("output_annotations", detections)
api.reportProgress(0.9)
api.postValues("output_values", [detections.size()])
api.reportProgress(1.0)
