description = "Tessera standard library: FIFOs, streams, counters, UART"
dependencies {
    api(project(":tessera-core"))
    testImplementation(project(":tessera-test"))
}

// compile-time width/stage/@Fn checks
dependencies {
    "annotationProcessor"(project(":tessera-checker"))
    "testAnnotationProcessor"(project(":tessera-checker"))
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xplugin:TesseraCheck") }
