description = "Tessera Studio: draw, run, watch and prove designs in the browser"
plugins { application }
dependencies {
    api(project(":tessera-playground"))
    api(project(":tessera-cli"))
    api(project(":tessera-stdlib"))
    api(project(":tessera-emit-sv"))   // the SV view and the Analyse panel's Yosys run
    testImplementation(project(":examples"))
    testImplementation(project(":tessera-riscv"))
    testImplementation("org.junit.platform:junit-platform-testkit")   // a saved testbench run under JUnit itself
    runtimeOnly(project(":examples"))
    runtimeOnly(project(":tessera-riscv"))  // the RISC-V cores, to open and watch
}
application {
    mainClass.set("org.eninehq.tessera.studio.Studio")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// The front end is plain ES modules and CSS in src/main/web (one file per concern). For the
// release they are concatenated, in the order of src/main/web/manifest.txt, into one offline
// page, studio.html, served from the classpath; `-Pstudio.dev` serves the files one by one.
val bundleWeb by tasks.registering {
    val web = layout.projectDirectory.dir("src/main/web")
    val out = layout.buildDirectory.file("generated/web/org/eninehq/tessera/studio/studio.html")
    inputs.dir(web)
    outputs.file(out)
    doLast {
        val dir = web.asFile
        val manifest = dir.resolve("manifest.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val css = manifest.filter { it.endsWith(".css") }.joinToString("\n") { "/* $it */\n" + dir.resolve(it).readText() }
        val js = manifest.filter { it.endsWith(".js") }.joinToString("\n") {
            "// ---- $it\n" + dir.resolve(it).readText().replace("</script", "<\\/script")
        }
        val shell = dir.resolve("shell.html").readText()
        val page = shell.replace("/*@CSS@*/", css).replace("//@JS@", js)
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(page)
    }
}
// the guide (docs/studio-guide.md and its pictures) goes into the bundle too, for /guide
val bundleGuide by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.dir("docs")) { include("studio-guide.md", "studio-guide/*.png") }
    into(layout.buildDirectory.dir("generated/guide/org/eninehq/tessera/studio/guide"))
}
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/web"))
sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/guide"))
tasks.named("processResources") { dependsOn(bundleWeb, bundleGuide) }
tasks.named<Test>("test") { systemProperty("studio.web", layout.projectDirectory.dir("src/main/web").asFile.path) }
