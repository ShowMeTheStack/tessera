description = "Tessera front end B: plain Java methods compiled to hardware through Babylon code models"

dependencies {
    api(project(":tessera-core"))
    api(project(":tessera-pipeline"))
    testImplementation(project(":tessera-test"))
    testImplementation(project(":tessera-formal"))
    testImplementation(project(":tessera-riscv"))
    testImplementation(project(":examples"))
    testImplementation(project(":tessera-emit-sv"))
}

// Compile and test with the Babylon JDK (Java 28), registered in gradle.properties.
extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(28))
}
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.code", "-Xlint:-incubating"))
}
tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules", "jdk.incubator.code")
}
tasks.withType<Javadoc>().configureEach { enabled = false }
