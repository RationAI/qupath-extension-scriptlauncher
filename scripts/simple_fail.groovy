api.reportProgress(0.0)
println "Script starting..."

def inputRoi = api.getInputRoi()
if (inputRoi != null) {
	println "Input ROI present: ${inputRoi.getROI()}"
}

api.reportProgress(0.5)

throw new RuntimeException("Simulated processing failure: could not process image")

return 0
