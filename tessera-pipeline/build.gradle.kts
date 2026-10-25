description = "Tessera pipelines: stage tags, latency checks, stalls/flushes, schedule API with hazard inference"
dependencies {
    api(project(":tessera-core"))
    testImplementation(project(":tessera-test"))
}
