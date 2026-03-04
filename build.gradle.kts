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

repositories {
    mavenCentral()
    flatDir {
        dirs("/home/filip/QuPath-v0.6.0-Linux/QuPath/lib/app")
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
    // EmpathAPI interface — resolved via includeBuild("../EmpathAPI") in settings.gradle.kts
    // Both JARs are installed in QuPath extensions at runtime.
    compileOnly("io.empath:empath-api:0.1.0")
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
