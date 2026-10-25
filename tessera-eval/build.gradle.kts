description = "Evaluation harnesses: the error corpus, the javac tests, proofs about the component library, and the browser suite"
dependencies {
    implementation(project(":tessera-core"))
    implementation(project(":tessera-pipeline"))
    implementation(project(":tessera-stdlib"))
    implementation(project(":tessera-test"))
    implementation(project(":tessera-cli"))
    implementation(project(":tessera-checker"))
    implementation(project(":tessera-emit-sv"))
    implementation(project(":tessera-formal"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

val tesseraJdk = providers.gradleProperty("tessera.syntaxJdk")
    .orElse(System.getProperty("user.home") + "/tessera-jdk/jdk25-tessera/build/macosx-aarch64-server-release/images/jdk")

// ./gradlew :tessera-eval:errorCorpus  -> build/results/errors.csv
tasks.register<JavaExec>("errorCorpus") {
    group = "verification"
    description = "Compiles and elaborates every case of the error corpus; reports where each mistake is caught"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.eninehq.tessera.eval.ErrorCorpus")
    workingDir = rootDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(file("corpus/errors").path, rootProject.layout.buildDirectory.file("results/errors.csv").get().asFile.path, tesseraJdk.get())
}

tasks.test {
    // LibraryProofs on some properties only: -Pproofs=fifo
    systemProperty("proofs", providers.gradleProperty("proofs").getOrElse(""))
}
