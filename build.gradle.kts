plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

group = "qupath.ext"
version = "0.1.0"

val qupathLibDir = providers.gradleProperty("qupathLibDir")
    .orElse(providers.environmentVariable("QUPATH_LIB_DIR"))
    .orElse("${rootDir}/../QuPath-v0.6.0-Linux/QuPath/lib/app")
    .get()

repositories {
    mavenCentral()
    flatDir {
        dirs(qupathLibDir)
    }
}

dependencies {
    compileOnly(":qupath-core-0.6.0")
    compileOnly(":qupath-gui-fx-0.6.0")
    compileOnly(":qupath-extension-openslide-0.6.0")
    compileOnly("org.slf4j:slf4j-api:1.7.36")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    // Groovy — bundled with QuPath, needed to compile GroovyShell usage
    compileOnly(":groovy-4.0.26")
    // ScriptAPI interface — resolved via includeBuild("../ScriptAPI") in settings.gradle.kts
    // Both JARs are installed in QuPath lib/app at runtime.
    compileOnly("qupath.ext:script-api:0.1.0")
}
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to version
        )
    }
    from("src/main/resources") {
        include("META-INF/services/**")
    }
}
