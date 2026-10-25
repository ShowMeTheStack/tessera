plugins {
    id("org.eninehq.tessera")
}

description = "Tessera examples"
dependencies {
    implementation(project(":tessera-stdlib"))
    implementation(project(":tessera-emit-sv"))
    testImplementation(project(":tessera-test"))
    testImplementation("net.jqwik:jqwik:1.9.3")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.test {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
}

tessera {
    tops.addAll(
        "org.eninehq.tessera.examples.Designs::counter",
        "org.eninehq.tessera.examples.Designs::blinky",
        "org.eninehq.tessera.examples.Designs::alu",
        "org.eninehq.tessera.examples.Designs::uartTx")
    synthTarget.set("xc7")
    formalDepth.set(10)
}

// compile-time width/stage/@Fn checks
dependencies {
    "annotationProcessor"(project(":tessera-checker"))
    "testAnnotationProcessor"(project(":tessera-checker"))
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xplugin:TesseraCheck") }
