#!/usr/bin/env python3
"""Summarise a JFR recording of a simulator run by where the CPU samples land.

usage: jfr_profile.py <jfr-binary> <recording.jfr> [label] [--csv out.csv]

Takes the jdk.ExecutionSample events, classifies each sample by its top (innermost, inlined
frames included) Java frame, and prints the share of each category plus the top methods.
Categories: generated code (GeneratedChunk*), SimOps.memRead, other SimOps helpers, WideOps,
commit (AbstractSim.commit/resetActive/checkProperties), settle/dispatch (AbstractSim.settle/tick,
CompiledSim.eval, Interpreter), BatchSim, harness (peek/poke, Data, String), other.
"""
import collections
import json
import subprocess
import sys


def category(frame):
    m = frame["method"]
    cls = m["type"]["name"].replace("/", ".")
    name = m["name"]
    if "GeneratedChunk" in cls or "GeneratedCommit" in cls:
        return "generated code"
    if cls.endswith("SimOps"):
        return "SimOps.memRead" if name == "memRead" else "SimOps helpers"
    if cls.endswith("WideOps"):
        return "WideOps"
    if cls.endswith("AbstractSim") and name in ("commit", "resetActive", "checkProperties"):
        return "commit"
    if cls.endswith("AbstractSim") and name in ("settle", "tick", "step"):
        return "settle/dispatch"
    if cls.endswith("CompiledSim") or cls.endswith("Interpreter"):
        return "settle/dispatch" if cls.endswith("CompiledSim") else "interpreter"
    if cls.endswith("BatchSim"):
        return "BatchSim." + name
    if cls.endswith("AbstractSim") or cls.startswith("org.eninehq.tessera.core") or cls.startswith("java.lang.String") \
            or cls.endswith("SimBench") or cls.endswith("Netlist"):
        return "harness (peek/poke)"
    return "other"


def main():
    jfr, rec = sys.argv[1], sys.argv[2]
    label = sys.argv[3] if len(sys.argv) > 3 and not sys.argv[3].startswith("--") else rec
    out = subprocess.run([jfr, "print", "--json", "--stack-depth", "3", "--events", "jdk.ExecutionSample", rec],
                         check=True, capture_output=True, text=True).stdout
    events = json.loads(out)["recording"]["events"]
    cats = collections.Counter()
    methods = collections.Counter()
    for e in events:
        frames = e["values"]["stackTrace"]["frames"]
        if not frames:
            continue
        top = frames[0]
        cats[category(top)] += 1
        m = top["method"]
        methods[m["type"]["name"].replace("/", ".").split(".0x")[0] + "." + m["name"]] += 1
    n = sum(cats.values())
    print(f"{label}: {n} samples")
    for c, k in cats.most_common():
        print(f"  {c:28s} {k:7d} {100.0 * k / n:6.1f}%")
    print("  top methods (generated chunks merged by name):")
    for m, k in methods.most_common(12):
        print(f"    {m:70s} {k:7d} {100.0 * k / n:6.1f}%")
    if "--csv" in sys.argv:
        path = sys.argv[sys.argv.index("--csv") + 1]
        with open(path, "a") as f:
            for c, k in cats.most_common():
                f.write(f"{label},{c},{k},{100.0 * k / n:.1f}\n")


if __name__ == "__main__":
    main()
