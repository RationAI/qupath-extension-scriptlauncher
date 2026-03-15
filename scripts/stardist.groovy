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

def defaultModelPath = "/scripts/stardist_models/dsb2018_heavy_augment.pb"

def modelPath = "/scripts/stardist_models/dsb2018_heavy_augment.pb"

if (!Files.exists(Paths.get(modelPath))) {

        api.fail("Model path does not exist: ${modelPath}")
        return
    }
}

println "Using StarDist model: ${modelPath}"

api.reportProgress(0.1)

def stardist = StarDist2D.builder(modelPath)
    .normalizePercentiles(1, 99)
    .threshold(0.5)
    .pixelSize(0.5)
    .tileSize(1024)
    .build()

println "Running StarDist detection..."
if (inputRoi == null)
    stardist.detectObjects(imageData)
else
    stardist.detectObjects(imageData, [inputRoi])

api.reportProgress(0.8)

def detections = hierarchy.getDetectionObjects()
println "Detected ${detections.size()} objects"

api.postAnnotations("output_annotations", detections)
api.reportProgress(0.9)
api.postValues("output_values", [detections.size()])
api.reportProgress(1.0)
