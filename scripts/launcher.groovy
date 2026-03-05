/**
 * launcher.groovy — headless QuPath entry point for EMPAIA job execution
 *
 * Invoked by Docker as:  QuPath script /scripts/launcher.groovy
 *
 * Required environment variables:
 *   EMPAIA_BASE_API  — EMPAIA App API base URL (e.g. https://host/api/app/v3)
 *   EMPAIA_JOB_ID    — EMPAIA job UUID
 *   QUPATH_SCRIPT    — absolute path to the user Groovy script to run
 *
 * Optional:
 *   EMPAIA_TOKEN     — bearer token for authenticated access
 */

import qupath.ext.scriptlauncher.EmpathApiImpl
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiClient
import qupath.lib.images.servers.remote.EmpaiaRemoteWsiImageServer
import qupath.lib.images.ImageData
import groovy.lang.Binding
import groovy.lang.GroovyShell

// ── Read configuration from environment ─────────────────────────────────────
def baseApi = System.getenv("EMPAIA_BASE_API")
def jobId   = System.getenv("EMPAIA_JOB_ID")
def token   = System.getenv("EMPAIA_TOKEN")

if (!baseApi || !jobId) {
    println "ERROR: EMPAIA_BASE_API and EMPAIA_JOB_ID must be set"
    System.exit(1)
}

// ── 1. Connect to EMPAIA and open WSI ───────────────────────────────────────
def headers = token ? ["Authorization": "Bearer ${token}".toString()] : [:]
def client  = new EmpaiaRemoteWsiClient(baseApi, jobId, headers)

def md = client.fetchMetadata()
if (!md || md.width <= 0 || md.height <= 0 || !md.id) {
    println "ERROR: Invalid WSI metadata from EMPAIA"
    System.exit(1)
}

def server    = new EmpaiaRemoteWsiImageServer(client, md.id)
def imageData = new ImageData(server)
println "Opened EMPAIA WSI: ${md.id}"

// ── 2. Create EmpathApiImpl ──────────────────────────────────────────────────
def api = new EmpathApiImpl(baseApi, jobId, token, md.id, java.net.http.HttpClient.newHttpClient())

// ── 3. Fetch input ROI and add to hierarchy ──────────────────────────────────
def inputRoi = api.getInputRoi()
if (inputRoi != null) {
    imageData.getHierarchy().addObject(inputRoi)
    println "Added input_roi to hierarchy"
}

// ── 4. Resolve user script path ──────────────────────────────────────────────
def scriptPath = System.getenv("QUPATH_SCRIPT")
if (!scriptPath) {
    api.fail("QUPATH_SCRIPT environment variable not set")
    System.exit(1)
}

def scriptFile = new File(scriptPath)
if (!scriptFile.exists()) {
    api.fail("Script file not found: ${scriptPath}")
    System.exit(1)
}

// ── 5. Execute user script with api injected into Groovy binding ─────────────
// The binding makes 'api', 'imageData' and 'hierarchy' available as variables
// in the user script without any imports. The class loader is shared so that
// QuPath and extension classes are accessible from the user script.
def binding = new Binding([
    api:       api,
    imageData: imageData,
    hierarchy: imageData.getHierarchy()
])

try {
    new GroovyShell(this.class.classLoader, binding).evaluate(scriptFile)
    println "User script completed successfully"
} catch (Exception e) {
    api.fail("Script execution failed: ${e.getMessage()}")
    e.printStackTrace()
    System.exit(1)
}

// ── 6. Finalize the EMPAIA job ───────────────────────────────────────────────
api.finalizeJob()
println "Job finalized"
