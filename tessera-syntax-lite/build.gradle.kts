description = "Optional javac plugin (internal javac API): automatic local names and unsigned lowering for stock javac"

val javacExports = listOf("api", "code", "comp", "jvm", "tree", "util").flatMap {
    listOf("--add-exports", "jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED")
}

dependencies {
    implementation(project(":tessera-checker"))
    testImplementation(project(":tessera-core"))
    testImplementation(project(":tessera-emit-sv"))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(javacExports)
    // javac's internal API is the point of this module
    options.compilerArgs.add("-Xlint:-options")
}
tasks.withType<Test>().configureEach {
    jvmArgs(javacExports)
    systemProperty("tessera.lite.classes", layout.buildDirectory.dir("classes/java/main").get().asFile.path
            + File.pathSeparator + layout.buildDirectory.dir("resources/main").get().asFile.path)
    systemProperty("tessera.checker.classes", project(":tessera-checker").layout.buildDirectory.dir("classes/java/main").get().asFile.path
            + File.pathSeparator + project(":tessera-checker").layout.buildDirectory.dir("resources/main").get().asFile.path)
}
tasks.withType<Javadoc>().configureEach { enabled = false }
