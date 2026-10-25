description = "Tessera RISC-V: RV32I semantics (software + hardware from one source), cores"
dependencies {
    api(project(":tessera-pipeline"))
    api(project(":tessera-stdlib"))
    api(project(":tessera-emit-sv"))
    testImplementation(project(":tessera-test"))
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.test {
    // Verilator builds are memory-hungry; keep them from running alongside each other
    maxParallelForks = 1
    // benchmarks: ./gradlew :tessera-riscv:test --tests '*SimSpeedBenchmark' -Pbench
    systemProperty("bench", providers.gradleProperty("bench").isPresent.toString())
    // WorkloadTest (Embench, CoreMark; slow): -Pworkloads
    systemProperty("workloads", providers.gradleProperty("workloads").isPresent.toString())
    if (providers.gradleProperty("workloads").isPresent) {
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
        systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism",
            providers.gradleProperty("workloads").get().ifBlank { "3" })
        maxHeapSize = "6g"
        systemProperty("workloadNames", providers.gradleProperty("workloadNames").getOrElse(""))
        systemProperty("workloadSet", providers.gradleProperty("workloadSet").getOrElse(""))
        systemProperty("workloadsOut", providers.gradleProperty("workloadsOut").getOrElse(""))
    }
    // CoreMatrixTest on some cores only: -Pcores=C2,C5
    systemProperty("cores", providers.gradleProperty("cores").getOrElse(""))
    // SimSpeedAllCores elsewhere than build/results: -PsimspeedOut=<file>
    systemProperty("simspeedOut", providers.gradleProperty("simspeedOut").getOrElse(""))
}

// Export the cores for riscv-formal: ./gradlew :tessera-riscv:rvfiExport -PrvfiCores=<riscv-formal>/cores
tasks.register<JavaExec>("rvfiExport") {
    group = "verification"
    description = "Writes the cores in riscv-formal's cores/<name> layout"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.riscv.RvfiExport")
    args(listOf(providers.gradleProperty("rvfiCores").orElse(layout.buildDirectory.dir("riscv-formal/cores").get().asFile.path).get()) +
        providers.gradleProperty("rvfiOnly").orElse("").get().split(",").filter { it.isNotBlank() })
}

// compile-time width/stage/@Fn checks
dependencies {
    "annotationProcessor"(project(":tessera-checker"))
    "testAnnotationProcessor"(project(":tessera-checker"))
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xplugin:TesseraCheck") }
