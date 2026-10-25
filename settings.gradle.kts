pluginManagement {
    includeBuild("gradle-plugin")
}

rootProject.name = "tessera"

include(
    "tessera-core",
    "tessera-emit-sv",
    "tessera-sim",
    "tessera-test",
    "tessera-pipeline",
    "tessera-stdlib",
    "tessera-formal",
    "tessera-cli",
    "tessera-checker",
    "tessera-playground",
    "tessera-studio",
    "tessera-fpga",
    "tessera-riscv",
    "examples",
    "tessera-eval",
)

// The Babylon front end needs a Babylon (code reflection) JDK; see tessera-babylon/README.md
val babylonJdk = providers.gradleProperty("tessera.babylonJdk")
    .orElse(System.getProperty("user.home") + "/tessera-jdk/babylon/build/macosx-aarch64-server-release/images/jdk").get()
if (file("$babylonJdk/bin/javac").exists()) include("tessera-babylon")

// Designs written with the Tessera javac syntax; see javac-fork/README.md
val tesseraJdk = providers.gradleProperty("tessera.syntaxJdk")
    .orElse(System.getProperty("user.home") + "/tessera-jdk/jdk25-tessera/build/macosx-aarch64-server-release/images/jdk").get()
if (file("$tesseraJdk/bin/javac").exists()) include("examples-syntax")
