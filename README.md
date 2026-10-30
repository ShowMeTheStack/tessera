# Tessera

Tessera is a hardware design framework for FPGAs that lives inside the Java ecosystem. A design is
an ordinary Java program. Running it elaborates a typed circuit, and that circuit can then be
simulated in the same JVM, tested under JUnit, proved with bounded model checking and k-induction,
emitted as synthesizable SystemVerilog, and built into a bitstream with Gradle.

```java
public class Counter extends Module {
    public final Bool en = input("en", Bool.TYPE);
    public final UInt count;

    public Counter(int width) {
        count = output("count", UInt.w(width));
        UInt value = reg("value", UInt.w(width), 0);
        when(en, () -> value.set(value.addWrap(1)));
        count.set(value);
    }
}
```

The repository also holds:

- a family of RISC-V cores (RV32IM with Zicsr) written once and built in many shapes: single-cycle,
  pipelined, dual-issue, out-of-order with a reorder buffer, with execute-time branch recovery and a
  load/store queue, and out-of-order cores generated from the same model as the in-order ones;
- Tessera Studio, a browser-based schematic editor, simulator, waveform viewer, pipeline viewer and
  prover whose drawings are stored as Java;
- three ways of extending Java for hardware: a javac plugin that checks widths, stages and unsigned
  arithmetic on stock javac; a Babylon front end that turns plain Java methods into hardware; and a
  patch to javac that adds unsigned and bit-width types, slices, concatenation, operator overloading
  and trailing blocks;
- the flows to put the cores on a ZedBoard with Vivado or openXC7 and test them over JTAG against
  the instruction-set simulator.

![Tessera Studio running a counter](docs/screenshots/01-overview.png)

## Contents

- [Module map](#module-map)
- [Requirements](#requirements)
- [Building](#building)
- [The Tessera javac](#the-tessera-javac)
- [Stock javac: the checker and the syntax-lite plugin](#stock-javac-the-checker-and-the-syntax-lite-plugin)
- [Plain Java as hardware (Babylon)](#plain-java-as-hardware-babylon)
- [Writing a design](#writing-a-design)
- [Simulation and waveforms](#simulation-and-waveforms)
- [Formal verification](#formal-verification)
- [The RISC-V cores](#the-risc-v-cores)
- [Verifying the cores](#verifying-the-cores)
- [riscv-formal](#riscv-formal)
- [FPGA: the ZedBoard](#fpga-the-zedboard)
- [Quality of results](#quality-of-results)
- [Command line and Gradle plugin](#command-line-and-gradle-plugin)
- [Tessera Studio: running it](#tessera-studio-running-it)
- [Tessera Studio: a tour](#tessera-studio-a-tour)
- [Scripts](#scripts)
- [Troubleshooting](#troubleshooting)
- [License](#license)

## Module map

| Directory | Contents |
|---|---|
| `tessera-core` | Types (`UInt`, `SInt`, `Bool`, `Bits`, `HwEnum`, tagged unions, bundles, `Vec`), modules, clock domains, memories (including `Mem.port` for true dual-port memories), assertions, the `@Unsigned` annotation, source locations and diagnostics with suggested fixes. |
| `tessera-emit-sv` | SystemVerilog output, clean under Verilator `-Wall`. Memories with two ports are written in the vendors' true dual-port template. |
| `tessera-sim` | The simulators: an interpreter, a compiled simulator that generates JVM bytecode, `BatchSim` (64 lanes, bit-sliced), and an experimental `ParallelSim`. VCD and FST waveforms, time travel, hot swap, JFR events. |
| `tessera-test` | JUnit `@HwTest`, concurrent testbenches, Verilator cross-checks, Yosys runs. |
| `tessera-pipeline` | Pipelines from straight-line code (`stage()`), and generated pipelines: an in-order model plus a schedule gives an in-order or dual-issue pipeline with forwarding, stalls and recovery derived from the schedule; the same model plus an `OooSchedule` gives an out-of-order machine (`OutOfOrderPipeline`). |
| `tessera-stdlib` | Streams, FIFOs (also across clock domains), arbiters, AXI-Lite, register files, block RAM, `TrueDualPortRam`, `LiveValueTable` and `LvtRegFile` (a multi-write register file built from one-write RAMs), time units, truth tables. |
| `tessera-formal` | Bounded model checking and k-induction with SMT solvers (bitwuzla, yices, z3), several clock domains and asynchronous resets, BTOR2 output, a yosys-smtbmc cross-check. |
| `tessera-checker` | The javac plugin `-Xplugin:TesseraCheck`: widths, pipeline stages, `@Fn` purity, and `@Unsigned` arithmetic. Runs on stock javac. |
| `tessera-syntax-lite` | The javac plugin `-Xplugin:TesseraSyntaxLite`: automatic local names and unsigned lowering on stock javac, through javac's internal tree API. |
| `tessera-cli` | The `tessera` command: `emit`, `lint`, `synth`, `formal`, `btor2`, `sim`, `fix`. |
| `gradle-plugin` | The `org.eninehq.tessera` Gradle plugin. |
| `tessera-playground` | A design running live in the browser (LEDs, buttons, displays), with block diagrams and live reload. |
| `tessera-studio` | Tessera Studio (server in Java, front end in `src/main/web`). |
| `tessera-riscv` | RV32IM + Zicsr written once for the instruction-set simulator and for the hardware, and every core built from it. |
| `tessera-fpga` | The cores on a ZedBoard: an AXI-Lite SoC, Vivado and openXC7 builds, the JTAG board suite, out-of-context exports for quality-of-results runs. |
| `tessera-babylon` | Plain Java methods compiled to hardware through Babylon code reflection. Included only when a Babylon JDK is present. |
| `javac-fork` | `tessera-javac.patch`, the Tessera language changes to the javac of OpenJDK 25.0.4. |
| `examples` | Small designs (counter, blinky, ALU, UART) built with the Gradle plugin. |
| `examples-syntax` | Designs written in the Tessera javac syntax. Included only when the Tessera JDK is present. |
| `tessera-eval` | Test corpora and harnesses: an error corpus of hardware mistakes, the javac tests (for the Tessera javac and, in `javac-tests-stock`, for stock javac with the plugins), proofs about the component library, and the Playwright suite and screenshot script for Studio. |
| `docs` | The Studio guide (Studio serves it at `/guide`) and the screenshots in this file. |
| `scripts` | Test-program and toolchain builds, riscv-formal runs, bug injection, simulator benchmarks, FPGA flows. |

## Requirements

A JDK 25 is enough to build Tessera and to run most of its tests. Everything else is optional: tests
that need a missing tool are skipped, and modules that need a missing JDK are left out of the build.

| Tool | Version used | Needed for |
|---|---|---|
| JDK | 25 (Temurin 25.0.4) | everything; Gradle finds it through toolchains |
| Gradle | 9.5 (wrapper included) | everything |
| Verilator | 5.048 | lint, simulator cross-checks |
| Yosys | 0.64 | synthesis, equivalence checks, resource estimates in Studio |
| bitwuzla, yices or z3 | any recent | the formal back end |
| SymbiYosys, yices, bitwuzla, riscv-formal | | riscv-formal on the cores, bug injection |
| riscv64-elf-gcc | 15.2 | riscv-tests, riscv-arch-test, the Zicsr tests, the workloads |
| picolibc | 1.8.10, built for rv32im/ilp32 (and rv32i) without TLS | the workloads (Embench-IoT, CoreMark) |
| Node.js and Playwright | 25 / 1.48 | the Studio browser suite and the screenshots |
| Tessera JDK | OpenJDK 25.0.4 + `javac-fork/tessera-javac.patch` | `examples-syntax`, the Tessera javac tests |
| Babylon JDK | OpenJDK Babylon (`code-reflection` branch, Java 28) | `tessera-babylon` |
| Vivado | 2020.1 (the `kkrizka/vivado:2020.1` container) | ZedBoard builds, out-of-context timing |
| openXC7 | yosys + nextpnr-xilinx + prjxray | the open-source ZedBoard flow |
| openFPGALoader | | `flash` |
| A ZedBoard | Zynq-7020 | the board suite |

## Building

```sh
./gradlew build                     # compile everything and run the test suite
./gradlew build -x test             # compile only
./gradlew :tessera-riscv:test       # one module's tests
```

Results that tests record (the co-simulation matrix, workload cycle counts, proof times, simulator
speed and so on) are written as CSV files to `build/results/`. Another directory is chosen with
`-PresultsDir=<dir>`.

The two extra JDKs are looked for in fixed places:

- the Tessera JDK in `~/tessera-jdk/jdk25-tessera/build/macosx-aarch64-server-release/images/jdk`;
- the Babylon JDK in `~/tessera-jdk/babylon/build/macosx-aarch64-server-release/images/jdk`.

Elsewhere (or on Linux, where the build directory is `linux-x86_64-server-release`), pass the paths
with `-Ptessera.syntaxJdk=...` and `-Ptessera.babylonJdk=...`. Gradle's toolchain resolution must
also know both JDKs. That setting is machine-specific, so it belongs in `~/.gradle/gradle.properties`
rather than in the repository:

```properties
org.gradle.java.installations.paths=/path/to/babylon/images/jdk,/path/to/jdk25-tessera/images/jdk
```

A complete build with both JDKs and every tool installed, on Linux:

```sh
./gradlew build --continue \
  -Ptessera.babylonJdk=$BABYLON -Ptessera.syntaxJdk=$TESSERA_JDK \
  -Porg.gradle.java.installations.paths=$BABYLON,$TESSERA_JDK
```

With every tool installed and both extra JDKs, the suite runs about 10,800 JUnit tests (most of them the
co-simulation matrix of the cores); about 120 are opt-in (`-Pworkloads`, `-Pbench`) or need a tool
that is not always installed, and are skipped. It covers the type system and the diagnostics; the
interpreter, the compiled and the bit-sliced simulators, against each other and against Verilator;
the SystemVerilog output, linted with Verilator and synthesized with Yosys; formal checks of the
component library and of the examples, including designs with several clocks; the pipeline
generators; every RISC-V core against the ISS; the javac plugins; and Studio's server (code
generation, round trips between drawings and Java, waveforms, critical-path mapping, the
out-of-order view and the prover options).

## The Tessera javac

`javac-fork/tessera-javac.patch` adds the following to the javac of OpenJDK 25.0.4 (about 1,000
lines in 11 files):

| Syntax | Meaning | Translated to |
|---|---|---|
| `uint`, `ulong`, `ushort`, `ubyte` | unsigned integers | `int`/`long` with unsigned `/ % < <= > >= >>`, zero-extension, `toUnsignedString` |
| `uint<N>` (1..64) | N-bit unsigned, wraps at 2^N | `int`/`long` plus masking after `+ - * << ~ ++ --` |
| `bits<N>` | raw bit vector: only `& \| ^ ~ << >> == !=`, slices, concatenation | `int`/`long` |
| `123u`, `0xFFFF_FFFFu`, `18000000000000000000uL` | unsigned literals | constants |
| `x[i]`, `x[hi:lo]` | bit select (boolean), slice (`uint<hi-lo+1>`) | shifts and masks |
| `{a, b, c}` | concatenation, `a` most significant | shifts and ors |
| `a + b`, `x[7:4]`, `{a, b}`, `~a`, `a < b` on classes | operator overloading for methods annotated `@Operator("+")` | method calls |
| `when (c) { ... }.elseWhen (d) { ... }.otherwise { ... }` | a block after a call is a trailing `() -> { }` argument | lambdas |
| `UInt<8> x` | `@Width(8) UInt x`, checked by `-Xplugin:TesseraCheck` | type annotation |
| `UInt sum = a + b;` | locals of hardware types become named wires | `Data.nameLocal(a.add(b), "sum")` |

Everything becomes ordinary bytecode, so the output runs on any Java 25 runtime. Signed and unsigned
values never mix silently: `uint u = i;`, `int x = u;`, `u + i`, passing an `int` for a `uint`
parameter and boxing a `uint` are compile errors that suggest a cast. Constants that fit are
accepted (`uint x = 5;`).

The counter above, in this syntax:

```java
UInt value = reg(UInt.w(8), 0);
when (en) {
    value.set(value.addWrap(1));
}
output("hi", UInt.w(4)).set(value[7:4]);
```

### Building it

`scripts/build-tessera-jdk.sh` does all of it:

```sh
BOOT_JDK=/path/to/jdk-25 scripts/build-tessera-jdk.sh [dir]   # default dir: ~/tessera-jdk/jdk25-tessera
```

By hand, the steps are:

```sh
git clone --depth 1 --branch jdk-25.0.4+7 https://github.com/openjdk/jdk25u.git jdk25-tessera
cd jdk25-tessera
git checkout -b tessera-syntax
git am /path/to/tessera/javac-fork/tessera-javac.patch
bash configure --with-boot-jdk=/path/to/jdk-25 --disable-warnings-as-errors \
     --with-debug-level=release --with-native-debug-symbols=none --with-version-opt=tessera
make images
```

The JDK is then in `build/<conf>/images/jdk`, where `<conf>` is for example
`linux-x86_64-server-release` or `macosx-aarch64-server-release`. Notes per system:

- **Linux:** install the usual OpenJDK build dependencies first (on Debian or Ubuntu:
  `autoconf`, `zip`, `libasound2-dev`, `libcups2-dev`, `libfontconfig1-dev`, `libx11-dev`,
  `libxext-dev`, `libxrender-dev`, `libxrandr-dev`, `libxtst-dev`, `libxt-dev`). `configure` says
  which one is missing.
- **macOS:** the build wants Xcode's Metal shader compiler. With only the command-line tools
  installed, give `configure` a stub for `metal` and `metallib`; a headless JDK does not need
  shaders. The script writes such a stub to `build/metal-stub/` and passes
  `METAL=<stub>/metal METALLIB=<stub>/metallib`.
- **Windows:** the script covers Linux and macOS only. On Windows, follow OpenJDK's own build
  instructions (Cygwin or WSL with Visual Studio) with the same tag and patch.

Point Gradle at the result with `-Ptessera.syntaxJdk=<dir>/build/<conf>/images/jdk` and add the
same path to `org.gradle.java.installations.paths`. `examples-syntax` is then part of the build, and
`scripts/javac_tests.py [tessera-jdk] [stock-java]` runs the javac test suite in
`tessera-eval/javac-tests`.

## Stock javac: the checker and the syntax-lite plugin

Most of what the Tessera javac gives is also available on an unmodified JDK 25, through two plugins.

`-Xplugin:TesseraCheck` (module `tessera-checker`, public javac API only) checks `@Width` types,
pipeline stages and `@Fn` purity, and treats `int` and `long` values annotated `@Unsigned`
(`org.eninehq.tessera.core.Unsigned`, or any type-use annotation named `Unsigned`) as unsigned. It
rejects silent mixing of signed and unsigned values, as the Tessera javac does, and also rejects the
operators whose Java meaning is signed (`/ % < <= > >= >>`, string concatenation, widening) when
they are applied to unsigned values.

`-Xplugin:TesseraSyntaxLite` (module `tessera-syntax-lite`) rewrites what the checker would reject
into the JDK's unsigned methods: `/ %` become `divideUnsigned`/`remainderUnsigned`, comparisons use
`compareUnsigned`, `>>` becomes `>>>`, concatenation uses `toUnsignedString`, and widening to `long`
uses `Integer.toUnsignedLong`. It also names locals of hardware types after their variable, as the
Tessera javac does (`UInt sum = a.add(b);` becomes a wire called `sum`). It uses javac's internal
tree API, so the compiler needs the `jdk.compiler` packages exported.

```java
@Unsigned int u = 0xFFFF_FFFF;
System.out.println("" + u);        // 4294967295 with TesseraSyntaxLite; an error with TesseraCheck alone
```

Command line, after `./gradlew :tessera-checker:classes :tessera-syntax-lite:classes`:

```sh
P=tessera-checker/build/classes/java/main:tessera-checker/build/resources/main
P=$P:tessera-syntax-lite/build/classes/java/main:tessera-syntax-lite/build/resources/main
javac -processorpath $P -Xplugin:TesseraCheck -Xplugin:TesseraSyntaxLite \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
  -J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
  -cp <your class path> MyDesign.java
```

In Gradle, the modules of this repository already run the checker:

```kotlin
dependencies { "annotationProcessor"(project(":tessera-checker")) }
tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xplugin:TesseraCheck") }
```

`tessera-eval/javac-tests-stock` is the javac test suite rewritten in stock syntax (its
`not-ported.txt` lists the tests that have no stock form and why). `scripts/javac_features.py` runs
the original suite on the Tessera javac and the stock suite with the checker alone and with both
plugins, and writes which feature works with which mechanism to `build/results/javac-features.csv`.

## Plain Java as hardware (Babylon)

`tessera-babylon` compiles ordinary Java methods annotated `@Reflect` to hardware, through Project
Babylon's code models. The methods use `int`, `long`, `boolean`, enums and records:

```java
@Reflect static int alu(Alu.Op op, int a, int b) {
    return switch (op) { case ADD -> a + b; case SUB -> a - b; /* ... */ };
}
UInt y = (UInt) JavaHw.of(MyFns.class, "alu").apply(op, a, b);   // inside a module
```

The method is its own software model, and the hardware keeps Java's meaning: 32-bit wrapping `int`,
signed comparisons, and the difference between `>>` and `>>>`. Values known at elaboration are
folded, loops with constant bounds are unrolled, `if`, `switch`, `?:` and early `return` on hardware
values become multiplexers, locals become named wires, and `Hardware.stage()` inserts a pipeline
register.

Build the Babylon JDK from https://github.com/openjdk/babylon (branch `code-reflection`):

```sh
bash configure --with-boot-jdk=<JDK 27>
make images
```

On macOS with only the command-line tools, pass `METAL=<stub> METALLIB=<stub>` to `configure`, as
for the Tessera javac. Then pass `-Ptessera.babylonJdk=<babylon>/build/<conf>/images/jdk`.

## Writing a design

A module declares its ports in its constructor, builds registers, memories and logic, and drives its
outputs. Elaboration is running that constructor:

```java
var e = Tessera.elaborate(() -> new Counter(8));
SvEmitter.write(e.circuit(), Path.of("build/counter.sv"));
```

Memories are declared with `mem(name, type, depth)`, read with `read` (combinational) or `readSync`
(registered, as block RAM does), and written with `write(addr, data, we)`. `Mem.port(addr, en, we,
wdata)` gives a read-first port that also writes; two ports on one memory make a true dual-port RAM,
which the emitter writes in the template Vivado maps to one block RAM. The library wraps it as
`TrueDualPortRam`.

Assertions are part of the design: `Hw.assertThat`, `Hw.assume` and `Hw.cover`. The simulators check
them every cycle and the formal back end proves them.

Pipelines can be written as straight-line code with `stage()` boundaries, or generated. A generated
in-order pipeline is a model (what each step computes, with typed keys) plus a schedule (where each
step happens). The scheduled RISC-V cores use, for example:

```java
public static final Schedule FIVE_STAGE = Schedule.stages("IF", "ID", "EX", "MEM", "WB")
        .place("IF", "fetch").place("ID", "decode", READ_REGS).place("EX", "execute")
        .place("MEM", "memory").place("WB", WRITE_REGS, "retire");
```

Forwarding, stalls, flushes and recovery from branch prediction are derived from the schedule;
`withLanes(2)` gives an in-order dual-issue pipeline with the pairing rules derived as well. The same
model with an `OooSchedule` goes through `OutOfOrderPipeline`, which builds a Tomasulo machine with a
reorder buffer: renaming through a register alias table, a shared reservation station, result buses
with wake-up, and in-order commit. Options add execute-time branch recovery and loads that go out
of order through a load/store queue. The model can keep speculative state (a branch history, a
return-address stack) that the generator snapshots and repairs on a redirect.

## Simulation and waveforms

```java
try (var sim = Sim.create(e)) {                    // the compiled simulator by default
    sim.trace(Path.of("build/counter.fst"));      // .fst gives FST, anything else VCD
    sim.poke("en", 1);
    sim.step(10);
    long v = sim.peek("count");
}
```

- **The interpreter** (`Sim.Backend.INTERPRETER`) decodes the netlist once into flat arrays and runs
  them.
- **The compiled simulator** (`Sim.Backend.COMPILED`, the default; `-Dtessera.sim=interpreter`
  changes the default) generates JVM bytecode for the design. Simulators of one netlist share their
  generated classes (`-Dtessera.sim.shareClasses=false` turns that off), and each code-generation
  step can be switched off with `-Dtessera.sim.disable=<list>` (`consts`, `locals`, `dce`, `memread`,
  `commit`, `masks`) for comparisons.
- **`BatchSim`** runs 64 copies of a design at once, one per bit of a `long` (`poke(path, lane,
  value)`, `peek(path, lane)`), with its gate program compiled to bytecode. It is used for random
  testing of the cores.
- **`ParallelSim`** splits the combinational evaluation over threads. It is experimental, and pays
  only for large designs.

The simulators support time travel (snapshots plus replayed inputs), hot swap of a changed design
into a running simulation (registers and memories keep their values where names and widths match),
and JFR events. Waveforms are VCD or FST, with a `.types.json` sidecar that describes the Java types.

`scripts/simspeed.sh` measures simulator speed on an idle machine (all cores, and A/B runs of each
code-generation step against a base commit, through `scripts/sim_opt_ab.py`);
`scripts/jfr_profile.py` summarises where a JFR recording of a simulation spends its time.

## Formal verification

`tessera-formal` unrolls the circuit into SMT-LIB and asks a solver:

```java
Formal.Result r = Formal.bmc(e, 20);         // no assertion fails within 20 cycles of reset
Formal.Result p = Formal.prove(e, 4);        // k-induction with k = 4: PROVEN, FAILED or UNKNOWN
Map<String, Integer> c = Formal.cover(e, 20); // the first cycle each cover point can be reached
```

The solver is the first of bitwuzla, yices and z3 found on the PATH, or the one named in
`Formal.Options`. A failure comes with the shortest counterexample, which the simulators can replay.

Designs with several clock domains are modelled with one step per instant at which some clock may
tick. The options say what the environment may do:

- `freeAsyncResets`: after the reset cycles, a reset input that drives an asynchronous reset may be
  pulled at any step (on by default);
- `atLeastOneTick`: assume some clock has an edge at every step;
- `maxTickGap`: assume every clock has an edge at least every N steps;
- `arbitraryMemory`, `resetCycles` and `solver`.

`YosysFormal` runs the same check through Yosys and yosys-smtbmc, as an independent cross-check of
the SMT encoding. `Btor2Emitter` writes BTOR2 for other model checkers.

## The RISC-V cores

`tessera-riscv` describes RV32IM and Zicsr once (`Rv32i`, `Zicsr`). The same description drives the
instruction-set simulator `Rv32iIss` and the hardware. The main cores (`Cores.ALL`):

| Core | What it is |
|---|---|
| C1a | single-cycle, LUT RAM (`SingleCycleCore`) |
| C1b | multi-cycle: the single-cycle datapath with block RAM |
| C2 | five-stage, forwarding and stalls written by hand (`FiveStageCore`) |
| C3, C4, C5, C6 | three-, four-, five- and six-stage pipelines generated from one model and a schedule of two or three lines (`ScheduledCore`) |
| C7 | out-of-order: Tomasulo with a 16-entry reorder buffer and 8 reservation stations, recovery at commit (`OooCore`) |
| C8 | C5's schedule with a 64-entry BTB |
| C9 | C5's schedule with two lanes, in order |
| C10 | C9 with the BTB |
| C11 | C7 with execute-time branch recovery (RAT checkpoints and branch masks, 4 checkpoints) and a load/store queue (4 loads, 4 stores, store-to-load forwarding) (`OooSpecCore`) |

The variants, grouped as Studio lists them:

| Group | Cores | What they are |
|---|---|---|
| Out-of-order variants of C11 | C11-br, C11-tdp | C11-br: execute-time recovery alone, memory accessed at the head as in C7. C11-tdp: C11 with a true dual-port data memory, speculative loads on port A and committed stores on port B. |
| Generated from one model | C7g, C7g-4x3, C7g-er, C7g-er-4x3, C7g-lsq, C7g-er-lsq, C7g-er-lsq-4x3, C5g | Out-of-order cores generated by `OutOfOrderPipeline` from `GeneratedOooCore`'s model: C7's organisation (C7g), recovery at execute (`er`), loads out of order through a load/store queue (`lsq`), and small windows (4 ROB entries, 3 RS). C5g is the same model through the in-order generator. |
| Dual issue, memory in both lanes | C9s, C10s, C9s-rep, C10s-rep | Loads and stores in both lanes, on the two ports of a true dual-port data memory. `s`: a store followed by a memory access is split at issue. `s-rep`: such a pair issues, and lane 1 is replayed when both touch one word. |
| Dual issue, live-value-table register file | C9-lvt, C10-lvt | The register file built as a live-value table (one-write LUT RAMs per write and read port, and a table of last writers) instead of flip-flops and read multiplexers. |
| Branch predictors | C5-static, C5-btb8 … C5-btb64, C8-static, C8-btb8 … C8-btb64, C8-nodec, C10-dec, C8-gshare, C8-ras, C8-gsras, C10-gsras, C8-gs12ras, C10-gs12ras | Static prediction, BTBs of 8 to 64 entries, gshare (8 or 12 bits of history), an 8-entry return-address stack, and combinations. |
| gshare history sweep | C8-gs4, C8-gs6, C8-gs10, C8-gs12, C10-ras, C10-gs12 | Simulation-only points for choosing the history length. |
| Where the multiply finishes | C5-mmem, C5-mwb, C5-mmf, C6-mwb, C6-mmf, C5-m2wb, … | Schedules that place or split the multiply in different stages. |
| Earlier schedules | C5-v1, C6-v1 | C5 and C6 with the multiply finished in MEM. |

`Cores.get("C11").build(memWords, image)` builds any of them.

## Verifying the cores

**Co-simulation.** `CoreMatrixTest` runs every core on riscv-tests (rv32ui, rv32um), riscv-arch-test
(rv32i_m/I), the Zicsr programs in `tessera-riscv/src/test/zicsr`, 100 random programs, 50
branch-dense and 50 memory-dense random programs, and a Verilator lint. Every retired instruction is
checked against the ISS through the core's RVFI port. Further tests cover the variants: dense
store-and-load mixes for the load/store queues, directed programs for the predictors
(`PredictorTest`), lane pairing for the symmetric lanes (`SymmetricLaneTest`), the LVT cores
(`LvtCoreTest`), 64-lane random programs on `BatchSim` (`BatchSimCoreTest`), and the interpreter and
the compiled simulator in lockstep (`LockstepTest`).

Build the test binaries first:

```sh
scripts/riscv-tests.sh     # riscv-tests as flat binaries (pass/fail in gp at ECALL)
scripts/arch-test.sh       # riscv-arch-test 2.7.4 with reference signatures
scripts/zicsr-tests.sh     # the machine-mode test programs
./gradlew :tessera-riscv:test --tests '*CoreMatrixTest'
```

**Workloads.** `scripts/workloads.sh` fetches Embench-IoT 1.0 and CoreMark at pinned versions and
builds them with riscv64-elf-gcc and picolibc (`ISA=rv32i` builds them without M). `WorkloadTest`
runs them on every core with every retired instruction checked against the ISS:

```sh
scripts/workloads.sh && ./gradlew :tessera-riscv:test --tests '*WorkloadTest' -Pworkloads=3
```

`-Pworkloads=N` runs N cores at once; give more memory with `-PworkloadHeap=12g` (default 6g).

**Mutants.** `scripts/mutants.py` holds 88 source mutations of the cores and the generators (lost
squashes, wrong checkpoint restores, store-to-load forwarding mistakes, pairing rules, predictor
state, CSR updates and so on). It applies each one, runs co-simulation and riscv-formal, and records
the first check that catches it:

```sh
scripts/mutants.py --cosim-only
scripts/mutants.py --formal-only --jobs 8
scripts/mutants.py c11-fwd-partial c11-ld-unknown     # chosen mutants
```

**Other opt-in runs.**

```sh
./gradlew :tessera-riscv:test --tests '*SweepTest' -Pworkloads=2            # ROB and RS size sweep
./gradlew :tessera-riscv:test --tests '*SimSpeedAllCores' -Pbench           # simulation speed (idle machine)
./gradlew :tessera-riscv:test --tests '*DebugOverheadBench' -Pbench         # cost of the debugging features
./gradlew :tessera-eval:test --tests '*LibraryProofs' -Pproofs=fifo         # some of the library proofs
./gradlew :tessera-eval:test --tests '*LvtProofs' -Plvtproofs=build/results/lvt.csv
./gradlew :tessera-eval:errorCorpus                                         # where each corpus mistake is caught
./gradlew :tessera-riscv:smtHashes -PfakeSolver=$PWD/scripts/fake-smt.sh    # hashes of every formal problem
./gradlew :tessera-fpga:svHashes                                            # hashes of every design's SystemVerilog
scripts/studio_tests.sh                                                     # Studio: JUnit, then the browser suite
```

The two hash tasks show whether a change to the back ends changed what they produce.

## riscv-formal

The cores have an RVFI port (two channels for the dual-issue cores) and are exported for
[riscv-formal](https://github.com/YosysHQ/riscv-formal) with a configuration for each:

```sh
./gradlew :tessera-riscv:rvfiExport -PrvfiCores=<riscv-formal>/cores                    # every target
./gradlew :tessera-riscv:rvfiExport -PrvfiCores=<riscv-formal>/cores -PrvfiOnly=tessera_c11,tessera_c7g
```

The targets include `tessera_single`, `tessera_five`, `tessera_sched3`, `tessera_sched5`, `tessera_sched6`, `tessera_ooo`,
`tessera_bram5`, `tessera_fpga5`, one per variant (`tessera_c9s`, `tessera_c9_lvt`,
`tessera_c8_gsras`, …), C7 and C11 with a small window (`tessera_c7`, `tessera_c11`,
`tessera_c11_br`, `tessera_c11_tdp`: 4 ROB entries, 3 reservation stations) and with the board's window
to a bound (`tessera_c7_16x8`, `tessera_c11_16x8`), and the generated cores (`tessera_c7g`,
`tessera_c7g_er`, `tessera_c7g_er_lsq`, `tessera_c7g_16x8`). The Zicsr cores get the CSR checks,
including read-after-write checks of `mscratch`, `mie` and `mtvec`. Some targets carry a deliberate bug
and must fail: `tessera_five_bug_wbfwd`, `tessera_five_bug_loaduse` and
`tessera_c8_gsras_bug_rastrusted` (execute trusts the return-address stack).

A short run of a few cores:

```sh
scripts/riscv-formal.sh <riscv-formal checkout> tessera_five tessera_sched5
```

Long runs of many cores go in waves with a time cap. Each core's checks run under `make -k` in a
process group of its own, recorded in `build/formal-<wave>/jobs.tsv`, so that a run can be stopped
without touching anything else:

```sh
export RF=~/tessera-tools/riscv-formal
scripts/formal_wave.py nightly --cap-hours 12 --jobs 4 --parallel 2 tessera_c11 tessera_c7g
python3 scripts/formal_status.py --watch          # verdicts per core: PASS, FAIL, vacuous, errors, running
```

The verdicts are in `$RF/cores/<core>/checks/<check>/status` and are summarised in
`build/results/formal-<wave>.csv`. A failing check leaves a trace (`engine_0/trace.vcd`) and a log
in its directory. `scripts/formal_bg.py` starts single checks in the background with a chosen
solver, and `scripts/formal_ctl.py <jobs.tsv> status|stop|cont|term` inspects, pauses, resumes or
ends them by their recorded PIDs. SymbiYosys is taken from `$TESSERA_TOOLS/sby-install/bin`
(default `~/tessera-tools`) or the PATH. `scripts/solver-shims/bitwuzla` goes first on the PATH:
Yosys 0.64's yosys-smtbmc starts bitwuzla with options that bitwuzla 0.9 no longer accepts, and
the shim translates them.

## FPGA: the ZedBoard

`tessera-fpga` puts a core behind an AXI-Lite slave on the Zynq's M_AXI_GP0 port, at `0x43C00000`.
No software runs on the ARM. The host reaches the board through `xsct` over JTAG to program the
FPGA, load programs into the 64 KiB instruction and data memories, start the core, and read back
registers, memory and counters.

| Offset | |
|---|---|
| `0x00000` | CTRL: bit 0 run, bit 1 external interrupt |
| `0x00004` | STATUS: bit 0 halted |
| `0x00008` / `0x0000C` | cycles / retired instructions since start |
| `0x00010` | ID |
| `0x00014` | pc of the last retired instruction |
| `0x00080` | x0..x31 |
| `0x10000` / `0x20000` | instruction / data memory |

Vivado runs in the `kkrizka/vivado:2020.1` container on the machine the board is plugged into,
reached over ssh. `ZED_HOST` (or `-PzedHost`) names that machine, `user@zed-host` by default, and
builds go to `~/tessera-builds/` there (`ZED_DIR` changes it). Any core name from `Cores` works,
as do the older names `bram5`, `bram3` and `ooo`.

```sh
scripts/zed.sh build C5 50                        # Vivado: soc.bit + soc.xsa, fabric clock 50 MHz
scripts/zed.sh openxc7 C5 50                      # yosys + nextpnr-xilinx + prjxray
./gradlew :tessera-fpga:zedSuite -Pcore=C5        # program the board, run the suite, compare with the ISS
./gradlew :tessera-fpga:zedSuite -Pcore=C11 -Pflow=openxc7 -Pslowdown=2 -Prandom=100
./gradlew :tessera-fpga:zedSuite -Pcore=C5 -Pkeep # do not reprogram
./gradlew :tessera-fpga:flash -Pbitstream=soc.bit # openFPGALoader, over ssh
./gradlew :tessera-fpga:zedSuiteSim -Pcore=C5     # the same suite against the simulated SoC
scripts/zed.sh xsct my.tcl                        # any xsct script, in the build directory
```

`ZedSuite` runs riscv-tests, riscv-arch-test and random programs (and the workloads, when built) on
the board and on the ISS, and compares results, cycles and retired instructions. `ZedSuiteSim` runs
the same suite against the simulated SoC, so a board run can be compared with simulation cycle for
cycle. `ZedSuiteSimPreloop` also reproduces the board's clock-measuring loop before the suite, which
matters for cores whose predictor state carries over from one program to the next.

**Clock margin.** The fabric clock is the last argument of `zed.sh build` and `zed.sh openxc7`. To
find how far a core goes beyond its timed clock, build it at increasing clocks and run the board
suite at each until the board results or the cycle counts stop matching the simulation; the highest
passing clock is that core's margin on that board.

Notes on openXC7:

- nextpnr-xilinx does not time DSP48E1, block RAM or LUT RAM cells, so its Fmax is optimistic.
- C1a needs both memories in LUT RAM with `openxc7/lutrams_nextpnr.txt` (`LUTRAM_MEMS`, set by `scripts/zed.sh`).
- `openxc7/dsp_cascade_fix.py` removes constant-zero DSP cascade inputs, which nextpnr cannot route.

`tessera-fpga/vivado/tdp_gate.tcl` checks that Vivado infers one true dual-port block RAM from the
emitted template.

## Quality of results

```sh
./gradlew :tessera-fpga:qorExport          # out-of-context designs of the cores, variants and sweeps
./gradlew :tessera-fpga:genQorExport       # the generated cores and their hand-written references
scripts/qor.sh                             # Vivado: utilization and timing for each design
scripts/qor_paths.sh                       # the worst paths of each, as reports Studio can read
```

The exports name each design (`core_c5`, `var_c11_tdp`, `gen_c7g`, …). The path reports open in
Studio's Analyse pane, which maps each step of a path to the line of Java that made it.

## Command line and Gradle plugin

The `tessera` command (`./gradlew :tessera-cli:installDist`) takes a design as `--top <class>` or
`--top <class>::<method>`, on the class path, and writes to `--out` (default `build/tessera`):

| Command | What it does |
|---|---|
| `emit` | writes SystemVerilog |
| `lint` | runs Verilator |
| `synth` | runs Yosys for `generic`, `xc7`, `ice40` or `ecp5` |
| `formal` | checks the design's assertions |
| `btor2` | writes a model for external model checkers |
| `sim` | simulates from reset |
| `fix` | applies the suggested fixes of Tessera diagnostics to the source files, or prints them as JSON |

A project applies the Gradle plugin and lists its designs:

```kotlin
plugins { id("org.eninehq.tessera") }

tessera {
    tops.addAll("org.eninehq.tessera.examples.Designs::counter")
    synthTarget.set("xc7")
    formalDepth.set(10)
}
```

Each listed design gets `tesseraEmit`, `tesseraLint`, `tesseraSynth`, `tesseraSim`, `tesseraBtor2`,
`formal` (part of `check`) and `flash`, which programs a board with openFPGALoader (ZedBoard,
Arty A7, Basys 3, iCEBreaker, ULX3S).

```sh
./gradlew :examples:tesseraEmit :examples:formal
```

## Tessera Studio: running it

Studio is a Java server with a browser front end. Build and start it from the repository root:

```sh
./gradlew :tessera-studio:installDist
tessera-studio/build/install/tessera-studio/bin/tessera-studio --src examples/src/main/java
# Tessera Studio: http://127.0.0.1:8800/
```

or, without installing, `./gradlew :tessera-studio:run --args="--src examples/src/main/java"`.

| Option | Meaning |
|---|---|
| `--port N` | the port (default 8800) |
| `--src DIR[:DIR…]` | your project's Java sources; their modules appear in the palette and drawings are compiled with them |
| `--designs CLASS` | a registry of designs for the *Designs* menu: a class with a static `studioDesigns()` returning `Map<String, Supplier<? extends Module>>` (the RISC-V cores are always listed) |
| `--hz N` | the clock frequency assumed for `Clock`-based parts (default 1000) |
| `--dev [DIR]` | serve the front end from its sources (default `tessera-studio/src/main/web`), so edits show on reload |

The server listens on 127.0.0.1 only. To use a Studio that runs on another machine (a build server
with Vivado, Verilator and the solvers, say), start it there and forward the port over ssh:

```sh
ssh -L 8800:127.0.0.1:8800 user@server \
  'cd tessera && tessera-studio/build/install/tessera-studio/bin/tessera-studio --src examples/src/main/java'
# then open http://127.0.0.1:8800/ on your own machine
```

Anyone who can reach the port can drive the simulator and start builds, so do not expose it beyond
the loopback interface without a proxy that authenticates. Studio finds Yosys, Verilator and the SMT
solvers on the server's PATH, and the riscv-tests list appears when it runs inside the repository
with the test binaries built. Long runs are bounded: at most 4,096 snapshots and 16 million recorded
changes, adjustable with `JAVA_OPTS="-Dstudio.maxSnapshots=… -Dstudio.waveBudget=…"`. Builds for the
board use `ZED_HOST` and `ZED_DIR` as the scripts do.

The guide, `docs/studio-guide.md`, is served at `/guide` and opens from the book icon in the
toolbar. Every screenshot below was taken from a running Studio by
`tessera-eval/browser/screenshots.mjs`, which can be rerun to refresh them:

```sh
cd tessera-eval/browser && npm install && npx playwright install chromium
node screenshots.mjs http://127.0.0.1:8800/ ../../docs/screenshots          # every scene
node screenshots.mjs http://127.0.0.1:8800/ /tmp/shots 28 29                 # only some
```

## Tessera Studio: a tour

### The drawing

![The counter running](docs/screenshots/01-overview.png)

The default drawing is an 8-bit counter with an enable. It is running at 10 cycles per second: wires
carry their values, the selected register shows its value and the next one, and the waves below grow
as the cycles pass. The inspector on the right edits the register's name, width, reset value and
clock domain, and shows the two lines of Java the part stands for.

![The same drawing in the light theme](docs/screenshots/02-overview-light.png)

The same view in the light theme, toggled from the sun icon at the right of the toolbar. Every
colour is a theme variable, so wires, values and waves stay readable in both.

<img src="docs/screenshots/05-palette.png" alt="Searching the palette" width="420">

The palette groups the parts: ports, logic, arithmetic, selection, state, library parts and modules.
Typing filters it; here `reg` finds the register, the state machine, the delay line and the two
register files of the library, one of them the live-value-table file the dual-issue cores use.

![Problems as you draw](docs/screenshots/06-problems.png)

Studio checks the drawing as it changes. The output `unused` is driven by nothing, so the Problems
tab says so in one sentence, the part is marked in red on the canvas, and the fix is one click away
(*Tie it to 0*). Wire widths are labelled on the canvas, here a 4-bit and an 8-bit input meeting at
an adder. While there are errors the simulator keeps running the last good design.

![The drawing is Java](docs/screenshots/07-java.png)

A drawing is a Java class. The Java tab shows the class that the drawing generates, with the lines of
the selected part highlighted; saving writes this file, and it compiles with the rest of a project.

![Editing the Java](docs/screenshots/08-java-edit.png)

The Java can be edited in place. Here a line adds an output `odd` from bit 0 of the count. *Apply to
the drawing* reads the class back into the drawing; statements outside the drawing's style become
code parts rather than being lost.

![Hot swap](docs/screenshots/09-hot-swap.png)

After the edit, the new output and its slice appear on the canvas and the running simulation is
reloaded in place: the counter keeps its value, the new signal starts in the waves, and the wave
record notes the reload ("reloaded Counter at cycle …: kept 1 registers").

### State, memory, waves and time

![A state machine](docs/screenshots/10-fsm.png)

A state machine is one part, given as states, inputs, transitions and outputs. The inspector draws
its diagram and lights the current state and the transition that will be taken next; on the canvas
the part shows the state by name.

![A memory and its contents](docs/screenshots/11-memory.png)

A 16 × 8 memory written by a counter. Selecting it shows its words as a hex grid at the cycle shown;
words that changed since the last look are lit, a double-click writes a word, and going back in time
shows the old contents.

![Waveforms](docs/screenshots/12-waves.png)

The waves: `value` and `count` grouped as *counter*, `count` expanded into its bits, and a marker
named *here*. Cursors, zoom, search, analog display and export to VCD, FST and SVG are in the
toolbar above the plot.

![Time travel](docs/screenshots/13-time-travel.png)

A click on the waves goes back to that cycle. The canvas, the inspector and every value show the
past cycle, and the live dot turns amber. Running or stepping returns to the present; changing an
input while in the past forks a new future from there.

![Two clock domains](docs/screenshots/14-clocks.png)

Two counters on two clocks: `count` on the design's clock with period 3, `fcount` on a clock named
`fast` with period 1, and bit 0 of `fcount` crossing into the slow domain through a two-stage
synchronizer. Parts in the fast domain are labelled with it, and the waves show the two rates.

### Proving

<img src="docs/screenshots/15-prove-pane.png" alt="The prover's options for several clocks" width="340">

The Prove pane of the two-clock drawing, with the options opened: whether asynchronous resets may be
pulled after reset, whether some clock must tick at every step, and a bound on the steps between two
ticks of every clock. Only options changed from their defaults are sent to the prover.

![A counterexample](docs/screenshots/16-prove-counterexample.png)

The counter with an assertion that it stays below five. *Bounded* with depth 20 finds that it fails at
cycle 6 and replaces the run with the counterexample: the waves are marked "counterexample", and the
result names the assertion and its line in the generated Java.

![Replaying the counterexample](docs/screenshots/17-prove-replay.png)

*Go to the failing cycle* moves the whole tool to cycle 6 of the counterexample: the register holds
5, the comparison is false, and the assertion part is lit. *Back to the live run* restarts the design
from reset.

### Testing, the board and building

![A testbench](docs/screenshots/18-bench.png)

The testbench tab takes JUnit-style tests whose methods receive a `Simulator`. One passes; the other
fails with JUnit's message and the cycles it covered. *Save as a JUnit test* writes the class for the
project's own test suite.

![Board parts](docs/screenshots/19-board.png)

Ports can be drawn as board parts: switches, LEDs, push buttons and a hex digit. They work on the
canvas while the design runs, and the Board tab shows the same ports as a front panel.

![Building for the FPGA](docs/screenshots/22-fpga.png)

The FPGA tab places the drawing's ports on the ZedBoard (switches, buttons, LEDs) and says what does
not fit. *Build and program* runs Vivado on the build host and programs the board over JTAG.

![Logic levels and resources](docs/screenshots/21-analyse.png)

The Analyse pane: logic levels per module (the longest combinational paths, counted in operators,
from a register or input to a register or output), and the resources Yosys estimates for the
design.

![A critical path on the drawing](docs/screenshots/20-timing-drawing.png)

A multiply-accumulate with the Vivado timing report of its build. The worst path is numbered on the
canvas, part by part, and listed in the pane with the delay of each step and the Java line it came
from.

### The RISC-V cores

![The designs menu](docs/screenshots/03-designs-menu.png)

*Designs* lists the main cores C1a to C11 and then the groups of variants: the out-of-order variants
of C11, the cores generated from one model, the dual-issue cores with memory in both lanes or a
live-value-table register file, the branch predictors, the gshare sweep and the multiply schedules.

![A group of variants](docs/screenshots/04-designs-variants.png)

Each group is a submenu. Opening a core asks for a program: its benchmark loop, or one of the
riscv-tests when Studio runs inside the repository.

![The explorer on C5](docs/screenshots/24-explorer-c5.png)

A core is too large for one schematic, so it opens in the explorer. The left panel lists its signals
with their values at the cycle shown; picking one draws it with the logic that drives it, and a
double-click on a part traces further back.

![The register file after a riscv-test](docs/screenshots/25-regfile.png)

C5 after running `rv32ui-add` to the end. The register file in the inspector shows x3 (`gp`) as 1:
the test passed. The status line says where the run stopped (`core_halted == 1`).

![C5's pipeline](docs/screenshots/26-pipeline-c5.png)

The pipeline tab draws C5 as it runs: one row per instruction, one column per cycle, the stage in
each cell. Amber cells wait (the load-use stall of `add` after `lw`), red cells were flushed by the
loop's taken branch, and green marks the cycle an instruction left. Above, the generator's own report
of what it inferred from the schedule: where redirects resolve, where registers are read and
written, and what is forwarded from where.

![C10's two lanes](docs/screenshots/27-pipeline-c10.png)

The dual-issue C10. Each cell names the stage and the lane (`ID·0`, `ID·1`): pairs go through the
stages together, and an instruction moves from lane 1 to lane 0 when the one beside it has to wait
or leaves alone.

![C11's reorder buffer](docs/screenshots/28-rob-c11.png)

C11 is not a pipeline of stages, so the same tab draws its reorder buffer: each instruction from
dispatch to commit, in a reservation station (`RS`), executing, in the load queue (`LQ`), a store
waiting in the store queue (`SQ`), done (`OK`) and committed. The rows below count the ROB entries,
reservation stations, queue entries and branch checkpoints in use in every cycle.

![The generated C7g](docs/screenshots/29-rob-c7g.png)

C7g, generated from the model by `OutOfOrderPipeline`, in the same view. The box above the diagram is
the generator's account of the machine it built: what is carried with each item, what is renamed,
which unit computes what, and what happens at commit.

![C11-tdp's data memory](docs/screenshots/30-memory-tdp.png)

C11-tdp keeps its data memory in a true dual-port RAM: speculative loads read on port A while
committed stores read and write on port B. The inspector shows the memory's words; the waves show
both ports' data and port B's write enable in the same cycles.

![The live-value table of C9-lvt](docs/screenshots/31-lvt-table.png)

C9-lvt after `rv32ui-add`. Its register file is a live-value table: one RAM per write port and read
port, and a 32-entry table that records which write port wrote each register last. The inspector
shows that table, with a button that reads the registers through it.

![The registers read through the table](docs/screenshots/31-lvt-registers.png)

The same register file read through the table: for each register, the word from the bank the table
names. x3 is 1, as on C5.

![C10's retirement channels](docs/screenshots/32-waves-c10.png)

The two RVFI channels of C10 in the waves: both lanes retire in the same cycle when a pair issues,
and `core_pair` and `core_split` show the pairing decisions.

![C11's critical path mapped to the source](docs/screenshots/33-timing-c11.png)

The Vivado report of C11's out-of-context build, opened in the Analyse pane. Each step of the worst
path is shown with its
delay, its cell types and the line of `OooSpecCore.java` or `RvCore.java` that produced it, starting
at the reorder buffer's head pointer, and the signals of the path are drawn in the explorer.

![C5's schedule in the Analyse pane](docs/screenshots/34-analyse-c5.png)

The Analyse pane on C5: the longest logic paths of the core, then the pipeline schedule the generator
inferred, the same report the pipeline tab shows.

![The guide](docs/screenshots/23-guide.png)

The guide opens in a drawer beside the work, or in its own tab. It is a reference ordered by task,
with every shortcut, part, problem and API call.

## Scripts

| Script | |
|---|---|
| `build-tessera-jdk.sh` | builds the Tessera JDK |
| `jshell.sh` | jshell with Tessera on the class path |
| `riscv-tests.sh`, `arch-test.sh`, `zicsr-tests.sh` | build the RISC-V test programs |
| `workloads.sh` | builds Embench-IoT and CoreMark |
| `riscv-formal.sh` | riscv-formal on a few cores |
| `formal_wave.py`, `formal_status.py` | capped riscv-formal waves over many cores, and their progress |
| `formal_bg.py`, `formal_ctl.py` | single checks in the background, and pausing, resuming or ending them by PID |
| `solver-shims/bitwuzla` | lets Yosys 0.64's yosys-smtbmc start bitwuzla 0.9 |
| `mutants.py` | bug injection |
| `fake-smt.sh` | a stand-in solver that answers `unsat`, for `smtHashes` |
| `javac_tests.py` | the Tessera javac tests |
| `javac_features.py` | the javac tests on the Tessera javac and on stock javac with the plugins |
| `simspeed.sh`, `sim_opt_ab.py` | simulator speed, and interleaved A/B runs of simulator options |
| `jfr_profile.py` | where a simulation spends its time, from a JFR recording |
| `studio_tests.sh` | Studio's JUnit and browser tests |
| `zed.sh` | ZedBoard builds (Vivado, openXC7) and xsct |
| `qor.sh`, `qor_paths.sh` | out-of-context Vivado runs |

## Troubleshooting

- **"Unsupported class file major version" or a toolchain error:** Gradle needs a JDK 25. Check
  `java -version` and `JAVA_HOME`, or let Gradle's toolchain support find one.
- **`examples-syntax` or `tessera-babylon` is missing from the build:** the extra JDK was not found.
  Pass `-Ptessera.syntaxJdk` / `-Ptessera.babylonJdk` and add both to
  `org.gradle.java.installations.paths`.
- **Many tests are skipped:** they need a tool that is not on the PATH (Verilator, Yosys, a solver,
  SymbiYosys) or test binaries that are not built (`scripts/riscv-tests.sh`, `scripts/arch-test.sh`,
  `scripts/zicsr-tests.sh`, `scripts/workloads.sh`). Each skip says what it needs.
- **`WorkloadTest` reports no cases:** `build/workloads` does not exist; run `scripts/workloads.sh`.
- **Out of memory in the workload or sweep tests:** they run several cores at once; lower
  `-Pworkloads=N` or raise `-PworkloadHeap`.
- **`javac` cannot find the plugin classes, or `IllegalAccessError` from `com.sun.tools.javac`:**
  build `tessera-checker` and `tessera-syntax-lite` first, put both on `-processorpath`, and pass
  the six `--add-exports` options with `-J`.
- **`git am` of the javac patch fails:** the patch is for the tag `jdk-25.0.4+7` of
  `openjdk/jdk25u`. Start from a clean clone of that tag.
- **riscv-formal checks end with an error at once:** `sby` or a solver is not found. Put SymbiYosys on
  the PATH (or in `~/tessera-tools/sby-install/bin`); with bitwuzla 0.9 and Yosys 0.64, use the shim
  in `scripts/solver-shims`.
- **Studio is not reachable from another machine:** it binds to 127.0.0.1 on purpose. Use an ssh
  tunnel (see above).
- **Studio's Prove pane says no solver was found:** put z3, bitwuzla or yices on the PATH of the
  process that runs Studio.
- **Board builds cannot connect:** set `ZED_HOST` to the machine with the board and Docker, and make
  sure `ssh $ZED_HOST docker ps` works without a password.

## License

Tessera is licensed under the GNU General Public License, version 3 ([LICENSE](LICENSE)), with an
additional term under section 7(b): anyone who conveys Tessera or a work based on it must preserve
the author attribution "Tessera, by Jyotiprakash Mishra (https://github.com/ShowMeTheStack/tessera)"
in the source and in the Appropriate Legal Notices of any work that contains it. See [NOTICE](NOTICE).
Tessera Studio shows this notice under About Tessera (click the name in its toolbar).

`javac-fork/tessera-javac.patch` modifies OpenJDK source files and stays under the GNU General
Public License, version 2, with the Classpath Exception, the same terms as OpenJDK
([javac-fork/LICENSE](javac-fork/LICENSE)).

Copyright (C) 2026 Jyotiprakash Mishra.
