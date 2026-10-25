description = "Tessera test harness: testbench API, virtual-thread processes, Verilator cross-check, JUnit extension"
dependencies {
    api(project(":tessera-sim"))
    api(project(":tessera-emit-sv"))
    api(platform("org.junit:junit-bom:5.13.4"))
    api("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.platform:junit-platform-testkit")   // runs testbench classes as JUnit would
}
