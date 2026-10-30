#!/usr/bin/env python3
"""Interleaved A/B runs of the simulators, one JVM per run (SimBench).

usage: sim_opt_ab.py --java <java> --tree NAME=<repo dir> [--tree ...] --config NAME=TREE[:jvm flags] [--config ...]
                     --cores C5,C7,C9 [--backend compiled] [--iterations 1000000] [--reps 5] [--warm 2]
                     [--rounds 3] [--taskset 2,3] [--perf] --out <csv> [--jvm-out <csv>]

Each tree needs `./gradlew :tessera-riscv:simBenchClasspath` run first (it writes
tessera-riscv/build/simbench.classpath). For every round, every core and every config (in that
order, so configs are interleaved and a drift in machine load hits them alike) one JVM runs
`SimBench <core> <backend> <iterations> <reps>`. Each REP line becomes a CSV row (wall seconds,
the simulating thread's CPU seconds, rate) together with the 1-minute load average read just before
the JVM started. Reps below --warm are marked warm=0; the summary uses, per config, the median over
rounds of each JVM's median warm rep.

--perf (Linux, needs sudo perf): attaches `perf stat --per-thread -e instructions:u,cycles:u -D -1
--control fifo:...` to the JVM; SimBench enables counting exactly around the timed loop of each warm
rep. --jvm-out gets, per JVM, the user-mode instructions (and core clock cycles) retired by the
simulating thread (the busiest thread named java) and the instructions of the JIT compiler threads
during those loops, and instructions per simulated cycle. The instruction count does not depend on
how loaded the machine is (the clock-cycle count does, through the other hyperthread and caches).

A config NAME of the form label@backend runs that backend instead of --backend (e.g.
`--config par2@parallel2=new`), so different backends can be interleaved in one run.

The script fails if two configs disagree on cycles or on the final-state hash of a core.
"""
import argparse
import os
import statistics
import subprocess
import sys
import time


def perf_counts(text):
    """Per thread and event, summed over cpu_core and cpu_atom (a hybrid CPU reports them separately)."""
    per = {}
    for line in text.splitlines():
        f = line.strip().split(",")
        if len(f) < 4 or not f[1].strip().isdigit():
            continue
        ev = "instructions" if "instructions" in f[3] else "cycles" if "cycles" in f[3] else None
        if ev:
            per.setdefault(f[0].strip(), {}).setdefault(ev, 0)
            per[f[0].strip()][ev] += int(f[1])
    java = [d for c, d in per.items() if c.startswith("java-")]
    simd = max(java, key=lambda d: d.get("instructions", 0), default={})
    jit = sum(d.get("instructions", 0) for c, d in per.items() if c.startswith("C1 CompilerThre") or c.startswith("C2 CompilerThre"))
    return simd.get("instructions", 0), simd.get("cycles", 0), jit


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--java", required=True)
    ap.add_argument("--tree", action="append", default=[])
    ap.add_argument("--config", action="append", default=[])
    ap.add_argument("--cores", required=True)
    ap.add_argument("--backend", default="compiled")
    ap.add_argument("--iterations", type=int, default=1_000_000)
    ap.add_argument("--reps", type=int, default=5)
    ap.add_argument("--warm", type=int, default=2)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--taskset", default="")
    ap.add_argument("--heap", default="4g")
    ap.add_argument("--perf", action="store_true")
    ap.add_argument("--out", required=True)
    ap.add_argument("--jvm-out", default="")
    a = ap.parse_args()
    trees = dict(t.split("=", 1) for t in a.tree)
    configs = []
    for c in a.config:
        name, rest = c.split("=", 1)
        tree, _, flags = rest.partition(":")
        backend = name.split("@", 1)[1] if "@" in name else a.backend
        configs.append((name, tree, flags.split() if flags else [], backend))
    cp = {t: open(os.path.join(d, "tessera-riscv/build/simbench.classpath")).read().strip() for t, d in trees.items()}
    new = not os.path.exists(a.out)
    out = open(a.out, "a")
    if new:
        out.write("core,backend,config,round,rep,warm,cycles,seconds,cpu_seconds,hz,cpu_hz,hash,load1,start_unix\n")
    jout = None
    if a.jvm_out:
        jnew = not os.path.exists(a.jvm_out)
        jout = open(a.jvm_out, "a")
        if jnew:
            jout.write("core,backend,config,round,counted_sim_cycles,sim_thread_instructions,sim_thread_core_cycles,jit_instructions,"
                       "sim_instr_per_cycle,sim_ipc,load1,start_unix\n")
    results, cpu_results, ipc_results = {}, {}, {}
    behaviour = {}
    for rnd in range(a.rounds):
        for core in a.cores.split(","):
            for name, tree, flags, backend in configs:
                fifo = None
                if a.perf:
                    fifo = os.path.join(os.path.dirname(os.path.abspath(a.out)), f".perfctl-{os.getpid()}")
                    if os.path.exists(fifo):
                        os.unlink(fifo)
                    os.mkfifo(fifo)
                    flags = flags + [f"-Dsimbench.ctl={fifo}", f"-Dsimbench.warm={a.warm}"]
                cmd = (["taskset", "-c", a.taskset] if a.taskset else []) + [a.java, f"-Xmx{a.heap}"] + flags + [
                    "-cp", cp[tree], "org.eninehq.tessera.riscv.SimBench", core, backend, str(a.iterations), str(a.reps)]
                load1 = os.getloadavg()[0]
                t0 = time.time()
                p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, cwd=trees[tree])
                perf = None
                if a.perf:
                    time.sleep(0.3)
                    perf = subprocess.Popen(["sudo", "perf", "stat", "--per-thread", "-e", "instructions:u,cycles:u", "-x,", "-D", "-1",
                                             "--control", f"fifo:{fifo}", "-p", str(p.pid)],
                                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
                stdout, stderr = p.communicate()
                perf_text = perf.communicate()[1] if perf else ""
                if fifo:
                    os.unlink(fifo)
                if p.returncode != 0:
                    sys.exit(f"{name} {core} failed:\n{stdout}\n{stderr}")
                warm, warm_cpu = [], []
                total = 0  # simulated cycles of the warm reps (the ones perf counts)
                for line in stdout.splitlines():
                    f = line.split()
                    if not f or f[0] != "REP":
                        continue
                    rep, cycles, secs, hz, h = int(f[3]), int(f[4]), float(f[5]), float(f[6]), f[7]
                    kv = dict(x.split("=", 1) for x in f[8:] if "=" in x)
                    cpu = float(kv.get("cpu", "nan"))
                    behaviour.setdefault(core, set()).add((cycles, h))
                    w = rep >= a.warm
                    if w:
                        total += cycles
                        warm.append(hz)
                        warm_cpu.append(cycles / cpu)
                    out.write(f"{core},{backend},{name},{rnd},{rep},{int(w)},{cycles},{secs:.6f},{cpu:.6f},{hz:.0f},{cycles / cpu:.0f},"
                              f"{h},{load1:.2f},{t0:.0f}\n")
                out.flush()
                m, mc = statistics.median(warm), statistics.median(warm_cpu)
                results.setdefault((core, name), []).append(m)
                cpu_results.setdefault((core, name), []).append(mc)
                extra = ""
                if perf:
                    sim, clk, jit = perf_counts(perf_text)
                    ipc = sim / total if total else 0
                    hw_ipc = sim / clk if clk else 0
                    ipc_results.setdefault((core, name), []).append(ipc)
                    extra = f"  instr/sim-cycle {ipc:8.1f}  IPC {hw_ipc:4.2f}  JIT instr {jit / 1e9:6.2f} G"
                    if jout:
                        jout.write(f"{core},{backend},{name},{rnd},{total},{sim},{clk},{jit},{ipc:.1f},{hw_ipc:.3f},{load1:.2f},{t0:.0f}\n")
                        jout.flush()
                print(f"round {rnd} {core:4s} {name:22s} warm {m / 1e6:8.3f} MHz wall, {mc / 1e6:8.3f} MHz cpu  load1 {load1:5.2f}{extra}",
                      flush=True)
    for core, b in behaviour.items():
        if len(b) != 1:
            sys.exit(f"configs disagree on behaviour for {core}: {sorted(b)}")
    print("\nsummary: median over rounds of per-JVM median warm rate (MHz) by wall and by thread CPU time; spread = min..max over rounds")
    for core in a.cores.split(","):
        ref = statistics.median(cpu_results[(core, configs[0][0])])
        for name, _, _, _ in configs:
            r, rc = results[(core, name)], cpu_results[(core, name)]
            md, mc = statistics.median(r), statistics.median(rc)
            line = (f"  {core:4s} {name:22s} wall {md / 1e6:7.3f} ({min(r) / 1e6:.3f}..{max(r) / 1e6:.3f})  cpu {mc / 1e6:7.3f} "
                    f"({min(rc) / 1e6:.3f}..{max(rc) / 1e6:.3f})  cpu x{mc / ref:5.2f} vs {configs[0][0]}")
            if (core, name) in ipc_results:
                ir = ipc_results[(core, name)]
                ref_i = statistics.median(ipc_results[(core, configs[0][0])])
                line += f"  instr/cycle {statistics.median(ir):7.1f} ({min(ir):.1f}..{max(ir):.1f}) x{ref_i / statistics.median(ir):5.2f} fewer"
            print(line)
    print("behaviour identical across configs:", {c: sorted(b)[0] for c, b in behaviour.items()})


if __name__ == "__main__":
    main()
