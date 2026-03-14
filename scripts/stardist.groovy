import qupath.ext.stardist.StarDist2D

// 'api', 'imageData', 'hierarchy' are injected by the Java script runner

def inputRoi = api.getInputRoi()
if (inputRoi == null) {
    api.fail("No input_roi provided by EMPAIA")
    return
}

def modelPath = System.getenv('STARDIST_MODEL_PATH')
if (!modelPath) {
    api.fail("STARDIST_MODEL_PATH environment variable not set")
    return
}

def stardist = StarDist2D.builder(modelPath)
    .normalizePercentiles(1, 99)
    .threshold(0.5)
    .pixelSize(0.5)
    .tileSize(1024)
    .build()

println "Running StarDist detection..."
stardist.detectObjects(imageData, [inputRoi])

def detections = hierarchy.getDetectionObjects()
println "Detected ${detections.size()} objects"

api.postAnnotations("cell_detections", detections)
api.postValues("cell_count", [detections.size()])
