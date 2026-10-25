description = "Tessera formal verification: SMT-based BMC and k-induction, BTOR2 and Yosys/SymbiYosys flows"
dependencies {
    api(project(":tessera-sim"))
    api(project(":tessera-emit-sv"))
    testImplementation(project(":tessera-test"))
    testImplementation(project(":tessera-stdlib"))
}
