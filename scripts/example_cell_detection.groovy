/**
 * example_cell_detection.groovy
 *
 * Example EMPAIA analysis script — cell detection within the input ROI.
 *
 * Injected variables (do not import or instantiate these manually):
 *   api       — ScriptApi: post results and communicate with the platform
 *   imageData — ImageData: the opened EMPAIA WSI
 *   hierarchy — PathObjectHierarchy: QuPath object hierarchy
 *
 * This script:
 *   1. Reads the input ROI from EMPAIA
 *   2. Runs QuPath's built-in cell detection within that ROI
 *   3. Posts all detected cell polygons as output_annotations
 *   4. Posts the total cell count as output_values
 */

import qupath.lib.scripting.QP

// ── 1. Get the input ROI ─────────────────────────────────────────────────────
// api.getInputRoi() returns a PathObject annotation (rectangle) or null.
api.reportProgress(0.0)
def inputRoi = api.getInputRoi()
if (inputRoi == null) {
    // No ROI provided — analyse the entire slide
    println "No input_roi provided, analysing full slide"
} else {
    // Add to hierarchy so detection runs only within this region
    hierarchy.addObject(inputRoi)
    hierarchy.getSelectionModel().setSelectedObject(inputRoi)
    println "Using input_roi: ${inputRoi.getROI()}"
}
api.reportProgress(0.1)

// ── 2. Run cell detection ────────────────────────────────────────────────────
// Uses QuPath's StarDist-style simple cell detection as a baseline.
// Tune requestedPixelSizeMicrons, threshold, etc. for the specific assay.
QP.runPlugin(
    'qupath.imagej.detect.cells.WatershedCellDetection',
    imageData,
    '{"requestedPixelSizeMicrons": 0.5, ' +
    '"backgroundRadiusMicrons": 8.0, ' +
    '"backgroundByReconstruction": true, ' +
    '"medianRadiusMicrons": 0.0, ' +
    '"sigmaMicrons": 1.5, ' +
    '"minAreaMicrons": 10.0, ' +
    '"maxAreaMicrons": 400.0, ' +
    '"threshold": 0.1, ' +
    '"watershedPostProcess": true, ' +
    '"cellExpansionMicrons": 0.0, ' +
    '"includeNuclei": true, ' +
    '"smoothBoundaries": true, ' +
    '"makeMeasurements": false}'
)
api.reportProgress(0.8)

// ── 3. Collect detections ────────────────────────────────────────────────────
def detections = hierarchy.getDetectionObjects()
println "Detected ${detections.size()} cells"

// ── 4. Post results to EMPAIA ────────────────────────────────────────────────
api.postAnnotations("output_annotations", detections)
api.reportProgress(0.9)
api.postValues("output_values", [detections.size()])
api.reportProgress(1.0)
