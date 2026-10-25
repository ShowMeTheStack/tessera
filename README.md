# Tessera

Tessera is a hardware design framework for FPGAs that runs entirely inside the Java ecosystem.
A design is a Java program. Running it elaborates a typed circuit, which can then be:

- simulated in the same JVM (an interpreter and a bytecode-compiled simulator);
- tested under JUnit;
- proved with bounded model checking and k-induction;
- emitted as synthesizable SystemVerilog;
- built into a bitstream with Gradle.

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

The same design, written for the Tessera javac (`javac-fork/`):

```java
UInt value = reg(UInt.w(8), 0);
when (en) {
    value.set(value.addWrap(1));
}
output("hi", UInt.w(4)).set(value[7:4]);
```

Tessera extends Java at three levels:

1. **A javac plugin** (`-Xplugin:TesseraCheck`) checks widths, pipeline stages and `@Fn` purity at compile time.
2. **A Babylon front end** turns plain Java methods into hardware through Project Babylon's code models.
3. **A modified javac** adds unsigned and bit-width types, slices, concatenation, operator overloading and trailing blocks.

Pipelines are first class. An in-order model of a processor is kept apart from its schedule, and
the framework derives the following from the schedule:

- forwarding;
- stalls;
- recovery from branch prediction;
- pairing for dual issue.

The repository contains ten RISC-V configurations built from one RV32IM + Zicsr description, from
single-cycle to out-of-order. Tessera Studio is a browser editor whose drawings are stored as Java.

## Contents

- [Repository layout](#repository-layout)
- [Requirements](#requirements)
- [Building](#building)
- [Tests](#tests)
- [Command line and Gradle plugin](#command-line-and-gradle-plugin)
- [Tessera Studio](#tessera-studio)
- [The Tessera javac](#the-tessera-javac)
- [Plain Java as hardware (Babylon)](#plain-java-as-hardware-babylon)
- [RISC-V cores](#risc-v-cores)
- [Verification of the cores](#verification-of-the-cores)
- [ZedBoard](#zedboard)
- [Scripts](#scripts)
- [License](#license)

## Repository layout

| Directory | Contents |
|---|---|
| `tessera-core` | **Types:** `UInt`, `SInt`, `Bool`, `Bits`, `HwEnum`, tagged unions, bundles, `Vec`.<br>**Structure:** modules, clock domains, CDC lint, assertions.<br>**Diagnostics:** source locations and fix suggestions. |
| `tessera-emit-sv` | SystemVerilog output, clean under Verilator `-Wall`. |
| `tessera-sim` | **Simulators:** an interpreter and a bytecode-compiled simulator, at any width; 64-lane bit-sliced simulation.<br>**Waveforms:** VCD and FST.<br>**Debugging:** time travel, hot swap, JFR events. |
| `tessera-test` | JUnit `@HwTest`, concurrent testbenches, Verilator cross-checks, Yosys. |
| `tessera-pipeline` | **From code:** pipelines from straight-line code (`stage()`).<br>**From a model:** in-order and dual-issue pipelines generated from a model plus a schedule, with forwarding, stalls and prediction recovery derived from the schedule. |
| `tessera-stdlib` | **Data movement:** streams, FIFOs (also across clock domains), arbiters, AXI-Lite.<br>**Storage:** register files, block RAM.<br>**Other:** time units, truth tables. |
| `tessera-formal` | Bounded model checking and k-induction with SMT solvers (bitwuzla, yices, z3), BTOR2 output, a yosys-smtbmc flow. |
| `tessera-checker` | The javac plugin `-Xplugin:TesseraCheck`. |
| `tessera-cli` | The `tessera` command: `emit`, `lint`, `synth`, `formal`, `btor2`, `sim`, `fix`. |
| `gradle-plugin` | The `org.eninehq.tessera` Gradle plugin: `tesseraEmit`, `tesseraLint`, `tesseraSynth`, `tesseraSim`, `tesseraBtor2`, `formal`, `flash`. |
| `tessera-playground` | A design running live in the browser (LEDs, buttons, displays), with block diagrams and live reload. |
| `tessera-studio` | Tessera Studio: a schematic and block editor whose drawings are Java. It covers simulation, waveforms, formal checks, FPGA builds and critical-path mapping (web front end in `src/main/web`). |
| `tessera-riscv` | RV32IM + Zicsr written once for the instruction-set simulator and for the hardware, and the cores built from it. |
| `tessera-fpga` | The cores on a ZedBoard: an AXI-Lite SoC, Vivado and openXC7 builds, and a JTAG board test compared against the ISS. |
| `tessera-babylon` | Plain Java methods compiled to hardware through Babylon code reflection. Included only when a Babylon JDK is present. |
| `javac-fork` | `tessera-javac.patch`: the Tessera language changes to the javac of OpenJDK 25.0.4. |
| `examples` | Small designs (counter, blinky, ALU, UART) built with the Gradle plugin. |
| `examples-syntax` | Designs written in the Tessera javac syntax. Included only when the Tessera JDK is present. |
| `tessera-eval` | Test corpora and harnesses:<ul><li>an error corpus of hardware design mistakes;</li><li>positive and negative tests for the Tessera javac;</li><li>proofs about the component library;</li><li>the Playwright browser suite for Tessera Studio.</li></ul> |
| `docs` | The Tessera Studio guide. Studio serves it at `/guide`. |
| `scripts` | Builds of external test programs and toolchains, riscv-formal, bug injection, FPGA flows (see [Scripts](#scripts)). |

## Requirements

Only a JDK 25 is needed to build Tessera and run most of its tests. The other tools listed here are
optional. Tests that need a missing tool are skipped, and modules that need a missing JDK are left
out of the build.

| Tool | Version used | Needed for |
|---|---|---|
| JDK | 25 (Temurin 25.0.4) | everything; Gradle finds it through toolchains |
| Gradle | 9.5 (wrapper included) | everything |
| Verilator | 5.048 | lint, simulator cross-checks |
| Yosys | 0.64 | synthesis, equivalence checks |
| bitwuzla, yices or z3 | any recent | the formal back end |
| SymbiYosys, yices, riscv-formal | | riscv-formal, bug injection |
| riscv64-elf-gcc | 15.2 | building riscv-tests, riscv-arch-test, the Zicsr tests and the workloads |
| picolibc | 1.8.10, built for rv32im/ilp32 (and rv32i) without TLS | the workloads (Embench-IoT, CoreMark) |
| Node.js + Playwright | 25 / 1.48 | the Tessera Studio browser suite |
| Tessera JDK | OpenJDK 25.0.4 + `javac-fork/tessera-javac.patch` | `examples-syntax`, the javac tests |
| Babylon JDK | OpenJDK Babylon (`code-reflection` branch, Java 28) | `tessera-babylon` |
| Vivado | 2020.1 (the `kkrizka/vivado:2020.1` container) | ZedBoard builds, out-of-context timing |
| openXC7 | yosys + nextpnr-xilinx + prjxray | the open-source ZedBoard flow |
| openFPGALoader | | `flash` |

The two extra JDKs are found in fixed places by default:

- the Tessera JDK in `~/tessera-jdk/jdk25-tessera/build/<conf>/images/jdk`;
- the Babylon JDK in `~/tessera-jdk/babylon/build/<conf>/images/jdk`.

Other paths are given with `-Ptessera.syntaxJdk=...` and `-Ptessera.babylonJdk=...`. Gradle's
toolchain resolution must also be told about both JDKs, in `~/.gradle/gradle.properties`:

```properties
org.gradle.java.installations.paths=/path/to/babylon/images/jdk,/path/to/jdk25-tessera/images/jdk
```

## Building

```sh
./gradlew build                     # compile everything and run the test suite
./gradlew build -x test             # compile only
./gradlew :tessera-riscv:test       # one module's tests
```

Results that tests record are written as CSV files to `build/results/`. These include:

- the co-simulation matrix;
- workload cycle counts;
- library proof times;
- simulation speed.

Another directory is selected with `-PresultsDir=<dir>`.

### Building the Tessera JDK

```sh
BOOT_JDK=/path/to/jdk-25 scripts/build-tessera-jdk.sh [dir]   # default dir: ~/tessera-jdk/jdk25-tessera
```

The script:

1. clones `openjdk/jdk25u` at `jdk-25.0.4+7`;
2. applies `javac-fork/tessera-javac.patch` with `git am`;
3. builds the images.

On macOS with only the command-line tools, the Metal shader compiler is replaced by a stub, because
a headless JDK does not need it.

### Building the Babylon JDK

Build the Babylon JDK from https://github.com/openjdk/babylon (branch `code-reflection`):

```sh
bash configure --with-boot-jdk=<JDK 27>
make images
```

On macOS with only the command-line tools, pass `METAL=<stub> METALLIB=<stub>` to `configure`.

## Tests

`./gradlew build` runs every module's tests. On a machine with Verilator, Yosys and an SMT solver,
that is about 4,700 JUnit tests. The suite covers:

- the type system and the diagnostics;
- the interpreter, the compiled simulator and the bit-sliced simulator, cross-checked against each other and against Verilator;
- SystemVerilog output, linted with Verilator and synthesized with Yosys;
- formal checks of the component library and of the examples;
- the pipeline generator;
- every RISC-V core, against the ISS (see [Verification of the cores](#verification-of-the-cores));
- Tessera Studio's server: code generation, round trips between drawings and Java, waveforms, and critical-path mapping.

Some test sets are opt-in, because they take longer or need an idle machine:

```sh
./gradlew :tessera-riscv:test --tests '*CoreMatrixTest'                    # co-simulation matrix of all cores
scripts/workloads.sh && ./gradlew :tessera-riscv:test --tests '*WorkloadTest' -Pworkloads=3
./gradlew :tessera-riscv:test --tests '*SweepTest' -Pworkloads=2            # ROB and RS size sweep
./gradlew :tessera-riscv:test --tests '*SimSpeedAllCores' -Pbench           # simulation speed (idle machine)
./gradlew :tessera-riscv:test --tests '*DebugOverheadBench' -Pbench         # cost of the debugging features
./gradlew :tessera-eval:test --tests '*LibraryProofs' [-Pproofs=fifo]       # proofs about the component library
./gradlew :tessera-eval:errorCorpus                                         # where each corpus mistake is caught
scripts/javac_tests.py [tessera-jdk] [stock-java]                           # the Tessera javac tests
scripts/studio_tests.sh                                                     # Studio: JUnit, then the browser suite
```

`studio_tests.sh` installs the browser suite's Node packages and Chromium in `tessera-eval/browser`
the first time it runs.

`FstWriterTest` and the browser suite's FST spec read the written waveforms back with
`~/tessera-tools/fstdump/fstdump`. This is GTKWave's reader, built from Verilator's copy of
`fstapi.c`:

```sh
V=$(verilator --getenv VERILATOR_ROOT)/include/gtkwave
cc -O1 -I$V -o fstdump fstdump.c $V/fstapi.c $V/lz4.c $V/fastlz.c -lz
```

When it is not built, those tests are skipped.

## Command line and Gradle plugin

The `tessera` command (`./gradlew :tessera-cli:installDist`) takes a design as `--top <class>` or
`--top <class>::<method>`, which must be on the class path, and writes to `--out` (default
`build/tessera`). The Gradle plugin below runs it with a project's class path. Its commands are:

| Command | What it does |
|---|---|
| `emit` | writes SystemVerilog |
| `lint` | runs Verilator |
| `synth` | runs Yosys for `generic`, `xc7`, `ice40` or `ecp5` |
| `formal` | checks the design's assertions |
| `btor2` | writes a model for external model checkers |
| `sim` | simulates from reset |
| `fix` | applies the suggested fixes of Tessera diagnostics to the source files; can also give the diagnostics as JSON |

A project applies the Gradle plugin and lists its top-level designs:

```kotlin
plugins { id("org.eninehq.tessera") }

tessera {
    tops.addAll("org.eninehq.tessera.examples.Designs::counter")
    synthTarget.set("xc7")
    formalDepth.set(10)
}
```

Every listed design then gets these tasks:

- `tesseraEmit`, `tesseraLint`, `tesseraSynth`, `tesseraSim` and `tesseraBtor2`;
- `formal`, which is part of `check`;
- `flash`, which programs a board with openFPGALoader (ZedBoard, Arty A7, Basys 3, iCEBreaker, ULX3S).

```sh
./gradlew :examples:tesseraEmit :examples:formal
```

## Tessera Studio

```sh
./gradlew :tessera-studio:run --args="--src examples/src/main/java"
# then open http://localhost:8800
```

Options: `--port <n>`, `--src <dirs>` (a project's sources whose modules can be used in drawings),
`--hz <n>`.

Studio is a schematic and block editor. A drawing is stored as a Java class, and a Java module can
be opened as a drawing. In the editor you can:

- run the simulator with live values on the wires;
- inspect waveforms with time travel, and export them as VCD, FST and SVG;
- run the formal back end on a drawing's assertions;
- view pipelines as they run, with one row per instruction.

For the board, you can:

- build a drawing for the ZedBoard with Vivado or openXC7;
- program the board;
- map Vivado's critical paths back to parts and source lines.

The guide is in `docs/studio-guide.md` and is served at `/guide`.

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

Everything is translated into ordinary bytecode, so the output runs on any Java 25 runtime.

Signed and unsigned values never mix silently. Each of these is a compile error that suggests a
cast:

- `uint u = i;` and `int x = u;`;
- `u + i`;
- `f(i)` for a `uint` parameter;
- boxing a `uint`.

Constants that fit are accepted (`uint x = 5;`).

## Plain Java as hardware (Babylon)

`tessera-babylon` compiles ordinary Java methods to hardware. The methods can use `int`, `long`,
`boolean`, enums and records, and are annotated with `@Reflect`:

```java
@Reflect static int alu(Alu.Op op, int a, int b) {
    return switch (op) { case ADD -> a + b; case SUB -> a - b; /* ... */ };
}
UInt y = (UInt) JavaHw.of(MyFns.class, "alu").apply(op, a, b);   // inside a module
```

The same method serves as its own software model. The hardware keeps its semantics: 32-bit
wrapping `int`, signed comparisons, and the difference between `>>` and `>>>`. In compiling a
method:

- values known at elaboration are folded;
- loops with constant bounds are unrolled;
- `if`, `switch`, `?:` and early `return` on hardware values become multiplexers;
- locals become named wires;
- `Hardware.stage()` inserts a pipeline boundary.

## RISC-V cores

`tessera-riscv` describes RV32IM + Zicsr once. The same description drives the instruction-set
simulator (`Rv32iIss`) and the hardware. The configurations in `Cores.ALL` are:

| Core | Built from |
|---|---|
| C1a, C1b | `SingleCycleCore`: C1a single-cycle with LUT RAM, C1b multi-cycle with block RAM |
| C2 | `FiveStageCore`, with hand-written forwarding and stalls |
| C3–C6 | `ScheduledCore` with three-, four-, five- and six-stage schedules; the schedule is 2–3 lines |
| C7 | `OooCore`: Tomasulo with a reorder buffer; ROB and RS sizes are parameters |
| C8 | C5's schedule with a 64-entry BTB |
| C9 | C5's schedule with two lanes, in order |
| C10 | C9 with the BTB |

The scheduled cores (C3–C6, C8–C10) share one in-order model. Forwarding, stalls, prediction
recovery and the pairing of two lanes are derived from each schedule.

## Verification of the cores

**Co-simulation.** Every core runs these programs and is checked against the ISS on every retired
instruction:

- riscv-tests (rv32ui, rv32um);
- riscv-arch-test (rv32i_m/I);
- the Zicsr tests in `tessera-riscv/src/test/zicsr`;
- random programs.

Build the test binaries with these scripts:

```sh
scripts/riscv-tests.sh     # riscv-tests as flat binaries (pass/fail in gp at ECALL)
scripts/arch-test.sh       # riscv-arch-test 2.7.4 with reference signatures
scripts/zicsr-tests.sh     # the machine-mode test programs
```

**riscv-formal.** The cores are exported with RVFI and checked by riscv-formal:

```sh
scripts/riscv-formal.sh <riscv-formal checkout> [core ...]
```

The cores with deliberate bugs, `tessera_five_bug_wbfwd` and `tessera_five_bug_loaduse`, are
expected to fail.

**Bug injection.** `scripts/mutants.py` applies source patches (mutants) to the cores. It runs each
mutant through co-simulation and through riscv-formal, and records the first check that fails:

```sh
scripts/mutants.py --cosim-only
scripts/mutants.py --formal-only
```

**Workloads.** `scripts/workloads.sh` fetches Embench-IoT 1.0 and CoreMark at pinned versions and
builds them with riscv64-elf-gcc and picolibc. `WorkloadTest` runs them on every core and checks
every retired instruction against the ISS. `ISA=rv32i` builds them without the M extension.

## ZedBoard

`tessera-fpga` places a core behind an AXI-Lite slave on the Zynq's M_AXI_GP0 port, at
`0x43C00000`. No software runs on the ARM. The host connects through `xsct` over JTAG to:

- program the FPGA;
- load programs into the 64 KiB instruction and data memories;
- start the core;
- read back registers, memory and counters.

The SoC's registers:

| Offset | |
|---|---|
| `0x00000` | CTRL: bit 0 run, bit 1 external interrupt |
| `0x00004` | STATUS: bit 0 halted |
| `0x00008` / `0x0000C` | cycles / retired instructions since start |
| `0x00010` | ID |
| `0x00014` | pc of the last retired instruction |
| `0x00080` | x0..x31 |
| `0x10000` / `0x20000` | instruction / data memory |

`ZedSuite` runs every program on the board and on the ISS and compares them. `ZedSuiteSim` runs the
same code against the simulated SoC.

```sh
scripts/zed.sh build bram5 50                                   # Vivado: soc.bit + soc.xsa
scripts/zed.sh openxc7 bram5 50                                 # yosys + nextpnr-xilinx + prjxray
./gradlew :tessera-fpga:zedSuite -Pcore=bram5                   # board test
./gradlew :tessera-fpga:zedSuite -Pcore=ooo -Pflow=openxc7 -Pslowdown=2
./gradlew :tessera-fpga:flash -Pbitstream=soc.bit               # openFPGALoader, over ssh
./gradlew :tessera-fpga:zedSuiteSim -Pcore=bram5                # the same suite in simulation
```

Vivado runs in the `kkrizka/vivado:2020.1` container on the machine the board is plugged into,
reached over ssh. `ZED_HOST` or `-PzedHost` names that machine, and builds go to `~/tessera-zed/`
there.

Notes on the openXC7 flow:

- nextpnr-xilinx does not time DSP48E1, block RAM or LUT RAM cells, so its Fmax is optimistic.
- C1a needs both memories mapped to LUT RAM with `openxc7/lutrams_nextpnr.txt` (`LUTRAM_MEMS`, set by `scripts/zed.sh`).
- `openxc7/dsp_cascade_fix.py` removes constant-zero DSP cascade inputs, which nextpnr cannot route.

Out-of-context timing:

- `./gradlew :tessera-fpga:qorExport` exports the cores;
- `scripts/qor.sh` and `scripts/qor_paths.sh` run Vivado on them for utilization, timing and the worst paths.

Studio maps those paths back to the design.

## Scripts

| Script | |
|---|---|
| `build-tessera-jdk.sh` | builds the Tessera JDK |
| `jshell.sh` | jshell with Tessera on the class path |
| `riscv-tests.sh`, `arch-test.sh`, `zicsr-tests.sh` | build the RISC-V test programs |
| `workloads.sh` | builds Embench-IoT and CoreMark |
| `riscv-formal.sh` | riscv-formal on the cores |
| `mutants.py` | bug injection |
| `javac_tests.py` | the Tessera javac tests |
| `studio_tests.sh` | Studio's JUnit and browser tests |
| `zed.sh` | ZedBoard builds (Vivado, openXC7) |
| `qor.sh`, `qor_paths.sh` | out-of-context Vivado runs |

## License

Tessera is licensed under the GNU General Public License, version 2, with the Classpath Exception,
the same terms as OpenJDK. `javac-fork/tessera-javac.patch` modifies OpenJDK source files and is
under the same terms. See [LICENSE](LICENSE).

Copyright (C) 2026 Jyotiprakash Mishra.
