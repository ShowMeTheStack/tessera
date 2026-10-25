description = "Tessera playground: virtual board in the browser, live reload, block editor, block diagrams"
plugins { application }
dependencies {
    api(project(":tessera-cli"))
    api(project(":tessera-stdlib"))
    testImplementation(project(":examples"))
    runtimeOnly(project(":examples")) // demo designs for ":tessera-playground:run"
}
application {
    mainClass.set("org.eninehq.tessera.playground.Playground")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// used by scripts/jshell.sh
tasks.register("printClasspath") {
    val cp = sourceSets["test"].runtimeClasspath
    doLast { println(cp.asPath) }
}
