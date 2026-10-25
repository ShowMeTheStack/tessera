description = "Tessera designs and software written with the Tessera javac syntax (uint, slices, operators, trailing blocks)"

dependencies {
    api(project(":tessera-core"))
    api(project(":tessera-stdlib"))
    testImplementation(project(":tessera-test"))
    "annotationProcessor"(project(":tessera-checker"))
    "testAnnotationProcessor"(project(":tessera-checker"))
}

// Compile with the Tessera JDK (vendor "N/A": a local OpenJDK build), registered in gradle.properties.
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.matching("N/A"))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xplugin:TesseraCheck")
}

dependencies { implementation(project(":tessera-emit-sv")); implementation(project(":tessera-pipeline")) }

// ./gradlew :examples-syntax:notationExport  -> build/notation/<design>/syntax.sv
tasks.register<JavaExec>("notationExport") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.notation.NotationExport")
    args(rootProject.layout.buildDirectory.dir("notation").get().asFile.path)
}
