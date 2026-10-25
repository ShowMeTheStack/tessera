description = "Tessera command line: emit, lint, synth, formal, sim (used by the Gradle plugin)"
plugins { application }
dependencies {
    api(project(":tessera-formal"))
    api(project(":tessera-test"))
    testImplementation(project(":examples"))
}
application {
    mainClass.set("org.eninehq.tessera.cli.TesseraCli")
    applicationName = "tessera"
}
