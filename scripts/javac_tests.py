#!/usr/bin/env python3
"""Run tessera-eval/javac-tests with the Tessera javac; class files run on a stock JDK.
Writes build/results/javac-tests.csv. Usage: scripts/javac_tests.py [tessera-jdk] [stock-java]"""
import csv, os, re, subprocess, sys, tempfile, time
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
TJDK = Path(sys.argv[1] if len(sys.argv) > 1 else Path.home() / "tessera-jdk/jdk25-tessera/build/macosx-aarch64-server-release/images/jdk")
JAVA = sys.argv[2] if len(sys.argv) > 2 else str(Path.home() / ".sdkman/candidates/java/25.0.4-tem/bin/java")
T = ROOT / "tessera-eval/javac-tests"
rows = []
for kind in ("pos", "neg"):
    for src in sorted((T / kind).glob("*.java")):
        text = src.read_text()
        what = re.search(r"^// \w+: (.*)$", text, re.M).group(1)
        out = Path(tempfile.mkdtemp())
        t0 = time.time()
        c = subprocess.run([str(TJDK / "bin/javac"), "-d", str(out), str(src)], capture_output=True, text=True)
        ms = (time.time() - t0) * 1000
        if kind == "pos":
            expect = re.findall(r"^// EXPECT: (.*)$", text, re.M)
            if c.returncode != 0:
                res, detail = "FAIL", "does not compile: " + c.stderr.strip().splitlines()[0]
            else:
                r = subprocess.run([JAVA, "-cp", str(out), src.stem], capture_output=True, text=True)
                got = r.stdout.strip().splitlines()
                res, detail = ("PASS", "") if got == expect else ("FAIL", f"printed {got}, expected {expect}")
        else:
            want = re.search(r"^// ERROR: (.*)$", text, re.M).group(1)
            line = next(i + 1 for i, l in enumerate(text.splitlines()) if "// HERE" in l)
            if c.returncode == 0:
                res, detail = "FAIL", "compiled, but should be rejected"
            else:
                m = re.search(r"\.java:(\d+): error: (.*)", c.stderr)
                at, msg = (int(m.group(1)), m.group(2)) if m else (-1, c.stderr.strip())
                ok = at == line and want in msg
                res, detail = ("PASS" if ok else "FAIL"), (msg if ok else f"line {at} (want {line}): {msg}")
        rows.append([src.stem, kind, what, res, f"{ms:.0f}", detail])
        print(f"{src.stem} {res:4} {what[:50]:50} {detail[:90]}")
(ROOT / "build/results").mkdir(parents=True, exist_ok=True)
with open(ROOT / "build/results/javac-tests.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["test", "kind", "what", "result", "javac_ms", "detail"])
    w.writerows(rows)
print(sum(r[3] == "PASS" for r in rows), "/", len(rows), "pass")
