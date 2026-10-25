plugins {
    `java-gradle-plugin`
}

group = "org.eninehq.tessera"
version = "0.1.0-SNAPSHOT"

repositories { mavenCentral() }

// plugins run inside the Gradle daemon, which may use an older JVM than the designs
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

gradlePlugin {
    plugins {
        create("tessera") {
            id = "org.eninehq.tessera"
            implementationClass = "org.eninehq.tessera.gradle.TesseraPlugin"
            displayName = "Tessera"
            description = "Hardware design in Java: emit SystemVerilog, lint, synthesize, verify formally, flash"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}
