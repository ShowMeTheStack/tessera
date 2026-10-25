description = "Tessera on FPGAs: AXI-Lite SoC wrapper for the RISC-V cores, ZedBoard build scripts"
plugins {
    application
    id("org.eninehq.tessera")
}
dependencies {
    api(project(":tessera-riscv"))
    api(project(":tessera-stdlib"))
    api(project(":tessera-emit-sv"))
    testImplementation(project(":tessera-test"))
}
application { mainClass.set("org.eninehq.tessera.fpga.ZedBuild") }
dependencies { testImplementation("org.junit.jupiter:junit-jupiter-params") }
// the riscv-tests / zicsr programs built for tessera-riscv
sourceSets["test"].resources.srcDir("../tessera-riscv/src/test/resources")
tasks.test { maxParallelForks = 1 }

// The board test: ./gradlew :tessera-fpga:zedSuite -Pcore=bram5 [-Pflow=openxc7] [-Prandom=100] [-Pslowdown=2] [-Pkeep: do not reprogram] [-PzedHost=user@zed-host] [-PzedDir=<build dir on the host>]
// (build the bitstream first with scripts/zed.sh build <core>)
tasks.register<JavaExec>("zedSuite") {
    group = "verification"
    description = "Runs the riscv-tests, riscv-arch-test and random programs on the ZedBoard, against the ISS"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.fpga.ZedSuite")
    val core = providers.gradleProperty("core").orElse("bram5").get()
    val flow = providers.gradleProperty("flow").orElse("vivado").get()   // vivado or openxc7
    args(providers.gradleProperty("zedHost").orElse("user@zed-host").get(),
        providers.gradleProperty("zedDir").orElse(if (flow == "openxc7") "tessera-zed/$core-openxc7" else "tessera-zed/$core").get(), core,
        providers.gradleProperty("random").orElse("100").get(), providers.gradleProperty("slowdown").orElse("1").get(),
        if (providers.gradleProperty("keep").isPresent) "keep" else "program")
    workingDir = rootDir
}

// ./gradlew :tessera-fpga:flash -Pbitstream=<local .bit>: programs the ZedBoard on the machine it is plugged into
tessera {
    board.set("zedboard")
    bitstream.set(providers.gradleProperty("bitstream").orElse(layout.buildDirectory.file("zed/soc.bit").get().asFile.path))
    flashHost.set(providers.gradleProperty("zedHost").orElse("user@zed-host"))
}

// Cores for the out-of-context quality-of-results comparison (vivado/qor.tcl, scripts/qor.sh)
tasks.register<JavaExec>("qorExport") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.fpga.QorExport")
    args(layout.buildDirectory.dir("qor").get().asFile.path)
    // -Pnames: also the emitter's name table beside each core.sv, for mapping timing paths back (Studio)
    if (providers.gradleProperty("names").isPresent) args("--names")
}

// The board suite against the simulated SoC, for the cycle-count comparison: ./gradlew :tessera-fpga:zedSuiteSim -Pcore=C5
tasks.register<JavaExec>("zedSuiteSim") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.fpga.ZedSuiteSim")
    args(providers.gradleProperty("core").orElse("C5").get(), providers.gradleProperty("random").orElse("100").get())
    workingDir = rootDir
    maxHeapSize = "4g"
}
