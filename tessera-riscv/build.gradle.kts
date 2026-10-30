description = "Tessera RISC-V: RV32I semantics (software + hardware from one source), cores"
dependencies {
    api(project(":tessera-pipeline"))
    api(project(":tessera-stdlib"))
    api(project(":tessera-emit-sv"))
    testImplementation(project(":tessera-test"))
    testImplementation(project(":tessera-formal"))
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
        // -PworkloadHeap=40g on a machine with the memory for many parallel workloads
        maxHeapSize = providers.gradleProperty("workloadHeap").getOrElse("6g")
        systemProperty("workloadNames", providers.gradleProperty("workloadNames").getOrElse(""))
        systemProperty("workloadSet", providers.gradleProperty("workloadSet").getOrElse(""))
        systemProperty("workloadsOut", providers.gradleProperty("workloadsOut").getOrElse(""))
    }
    // CoreMatrixTest on some cores only: -Pcores=C2,C5
    systemProperty("cores", providers.gradleProperty("cores").getOrElse(""))
    // SimSpeedAllCores elsewhere than build/results: -PsimspeedOut=<file>
    systemProperty("simspeedOut", providers.gradleProperty("simspeedOut").getOrElse(""))
    // PredictorTest's directed-program table : -PpredictorOut=<file>
    systemProperty("predictorOut", providers.gradleProperty("predictorOut").getOrElse(""))
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

// The test runtime classpath, for scripts/simspeed.sh, scripts/sim_opt_ab.py and profiling (java -cp "$(cat <file>)" ...SimBench)
tasks.register("simBenchClasspath") {
    val cp = sourceSets["test"].runtimeClasspath
    val out = layout.buildDirectory.file("simbench.classpath")
    dependsOn("testClasses", cp)
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.asPath) }
}

// compile-time width/stage/@Fn checks
dependencies {
    "annotationProcessor"(project(":tessera-checker"))
    "testAnnotationProcessor"(project(":tessera-checker"))
}
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xplugin:TesseraCheck") }

// Hashes of the SMT-LIB problems and BTOR2 models of every core, sent to a fake solver
// ./gradlew :tessera-riscv:smtHashes -PsmtOut=<file> -PfakeSolver=<script answering unsat>
tasks.register<JavaExec>("smtHashes") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.riscv.SmtHashes")
    jvmArgs("-Xss512m")
    val tee = layout.buildDirectory.file("smt-tee.smt2").get().asFile.path
    systemProperty("tessera.formal.tee", tee)
    args(providers.gradleProperty("smtOut").getOrElse(layout.buildDirectory.file("smt-hashes.txt").get().asFile.path),
        providers.gradleProperty("fakeSolver").getOrElse("fake-smt"), tee)
}
