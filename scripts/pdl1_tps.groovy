import qupath.ext.stardist.StarDist2D
import qupath.lib.images.ImageData.ImageType
import qupath.lib.images.servers.ColorTransforms
import java.nio.file.Files
import java.nio.file.Paths

// Estimates a TPS-style PD-L1 score: positive cells / all cells in the input ROI x 100.
// Every cell in the ROI is assumed to be a tumor cell, so the result is an estimate, not a clinical TPS.
// Injected by the Java script runner: api, imageData, hierarchy, server, selectionModel, project, args

// Thrown errors make the launcher fail the job. Calling api.failJob() and returning would still finalize it.
api.reportProgress(0.0)
def inputRoi = api.getInputRoi()
if (inputRoi == null)
    throw new IllegalArgumentException("input_roi is required: draw a box inside the tumor region")
println "Using input_roi: ${inputRoi.getROI()}"

def model         = api.getInput("model") ?: "he_heavy_augment"
def threshold     = (api.getInput("threshold")      ?: "0.5").toDouble()
def pixelSize     = (api.getInput("pixel_size")     ?: "0.5").toDouble()
def tileSize      = (api.getInput("tile_size")      ?: "1024").toInteger()
def cellExpansion = (api.getInput("cell_expansion") ?: "5.0").toDouble()
def dabThreshold  = (api.getInput("dab_threshold")  ?: "0.2").toDouble()

if (!(model in ["he_heavy_augment", "dsb2018_heavy_augment"]))
    throw new IllegalArgumentException("Unknown model: ${model}")
// Without expansion the outline stops at the nucleus and there is no membrane to measure
if (cellExpansion <= 0)
    throw new IllegalArgumentException("cell_expansion must be greater than 0")

def modelPath = "/scripts/models/stardist/${model}.pb"
if (!Files.exists(Paths.get(modelPath)))
    throw new IllegalStateException("Model path does not exist: ${modelPath}")

println "PD-L1 params: model=${model} threshold=${threshold} pixelSize=${pixelSize} tileSize=${tileSize} cellExpansion=${cellExpansion} dabThreshold=${dabThreshold}"

// Separates the brown DAB stain from the blue hematoxylin, using QuPath's default stain vectors
imageData.setImageType(ImageType.BRIGHTFIELD_H_DAB)
def stains = imageData.getColorDeconvolutionStains()

api.reportProgress(0.1)

def stardistBuilder = StarDist2D.builder(modelPath)
    .normalizePercentiles(1, 99)
    .threshold(threshold)
    .pixelSize(pixelSize)
    .tileSize(tileSize)
    .cellExpansion(cellExpansion)
    .measureIntensity()
// The dsb2018 model takes one channel, the H&E model takes RGB
if (model == "dsb2018_heavy_augment")
    stardistBuilder = stardistBuilder.channels(ColorTransforms.createColorDeconvolvedChannel(stains, 1))
def stardist = stardistBuilder.build()

println "Running StarDist detection..."
stardist.detectObjects(imageData, [inputRoi])

api.reportProgress(0.8)

def cells = hierarchy.getDetectionObjects()
println "Detected ${cells.size()} cells"
if (cells.isEmpty())
    throw new IllegalStateException("No cells detected in input_roi")

def measurement = "Membrane: DAB: Mean"
def measured = cells.findAll { !Double.isNaN(it.getMeasurementList().get(measurement)) }
if (measured.isEmpty())
    throw new IllegalStateException("No cell has the measurement '${measurement}'")
if (measured.size() < cells.size())
    println "Ignoring ${cells.size() - measured.size()} cells without '${measurement}'"

def positive = measured.findAll { it.getMeasurementList().get(measurement) > dabThreshold }
def negative = measured - positive
def score = 100.0d * positive.size() / measured.size()

println "PD-L1 score: ${score}% (${positive.size()} positive of ${measured.size()} cells)"
// Clinical TPS scoring requires at least 100 viable tumor cells
if (measured.size() < 100)
    println "WARNING: fewer than 100 cells, too few for a reliable score"

api.postAnnotations("positive_cells", positive)
api.postAnnotations("negative_cells", negative)
api.reportProgress(0.9)
api.postValues("score_percent", [score])
api.postValues("positive_count", [positive.size()])
api.postValues("total_count", [measured.size()])
api.reportProgress(1.0)
