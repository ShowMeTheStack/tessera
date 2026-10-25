description = "Tessera compile-time checks as a javac plugin: widths, pipeline stages, combinational purity"
dependencies {
    testImplementation(project(":tessera-core"))
    testImplementation(project(":tessera-pipeline"))
}
tasks.withType<Test>().configureEach {
    systemProperty("tessera.checker.classes", layout.buildDirectory.dir("classes/java/main").get().asFile.path)
}
