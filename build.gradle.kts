plugins {
    `java-library`
}

allprojects {
    group = "org.eninehq.tessera"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-this-escape"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "2g"
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        providers.gradleProperty("tessera.sim").orNull?.let { systemProperty("tessera.sim", it) }
        providers.gradleProperty("tessera.bench").orNull?.let { systemProperty("tessera.bench", it) }
        providers.gradleProperty("tessera.updateGolden").orNull?.let { systemProperty("tessera.updateGolden", it) }
        // tests that record results (matrix, workloads, proofs, simulation speed, ...) write CSV files here
        val results = providers.gradleProperty("resultsDir").getOrElse(rootProject.layout.buildDirectory.dir("results").get().asFile.path)
        systemProperty("resultsDir", results)
        doFirst { file(results).mkdirs() }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
