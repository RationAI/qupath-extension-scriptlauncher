api.reportProgress(0.0)

def roi = api.getInputRoi()

if (roi == null) {
    println 'WARNING: No input_roi found'
    api.reportProgress(1.0)
    return 0
}

println "Found input_roi: x=${roi.getROI().getBoundsX()}, y=${roi.getROI().getBoundsY()}, w=${roi.getROI().getBoundsWidth()}, h=${roi.getROI().getBoundsHeight()}"
println "Script completed successfully"


api.reportProgress(1.0)

api.postValues("output_values", [roi.getROI().getArea().intValue()])
api.postAnnotations("output_annotations", [])

