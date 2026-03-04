rootProject.name = "qupath-extension-scriptlauncher"

// Pull in EmpathAPI as a composite build so the IDE and compiler see its
// source directly, without needing to pre-build a JAR manually.
includeBuild("../EmpathAPI")
