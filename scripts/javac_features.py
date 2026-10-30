#!/usr/bin/env python3
"""Run the javac test suite three ways and write the feature x mechanism table.

  1. the original suite (tessera-eval/javac-tests) with the Tessera javac (class files run on stock Java), as
     scripts/javac_tests.py does, but written to OUT_DIR;
  2. the stock-syntax suite (tessera-eval/javac-tests-stock) on stock JDK 25 with -Xplugin:TesseraCheck ("check");
  3. the same with -Xplugin:TesseraCheck -Xplugin:TesseraSyntaxLite ("lite").
For "// LOWERED" positive tests, "check" expects a rejection (operators with signed Java meaning) and the
script also records what plain stock javac (no plugin) prints, to show that the rejection is needed.

Needs ./gradlew :tessera-checker:classes :tessera-syntax-lite:classes first.
Usage: scripts/javac_features.py [OUT_DIR]   (default build/results)
Env: STOCK_JDK (default ~/.sdkman/candidates/java/25.0.4-tem), TESSERA_JDK (default the local Tessera JDK).
Writes javac-tests-tessera.csv, javac-tests-stock.csv, javac-features.csv."""
import csv, os, re, subprocess, sys, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/results"
OUT.mkdir(parents=True, exist_ok=True)
STOCK = Path(os.environ.get("STOCK_JDK", Path.home() / ".sdkman/candidates/java/25.0.4-tem"))
TJDK = Path(os.environ.get("TESSERA_JDK", Path.home() / "tessera-jdk/jdk25-tessera/build/macosx-aarch64-server-release/images/jdk"))
PLUGINS = os.pathsep.join(str(ROOT / p) for p in (
    "tessera-checker/build/classes/java/main", "tessera-checker/build/resources/main",
    "tessera-syntax-lite/build/classes/java/main", "tessera-syntax-lite/build/resources/main"))
EXPORTS = [f"-J--add-exports=jdk.compiler/com.sun.tools.javac.{p}=ALL-UNNAMED" for p in ("api", "code", "comp", "jvm", "tree", "util")]
MODES = {"none": [], "check": ["-Xplugin:TesseraCheck"], "lite": ["-Xplugin:TesseraCheck", "-Xplugin:TesseraSyntaxLite"]}

def meta(text):
    what = re.search(r"^// \w+: (.*)$", text, re.M).group(1)
    expect = re.findall(r"^// EXPECT: (.*)$", text, re.M)
    err = re.search(r"^// ERROR: (.*)$", text, re.M)
    here = next((i + 1 for i, l in enumerate(text.splitlines()) if "// HERE" in l), None)
    return what, expect, err.group(1) if err else None, here, "// LOWERED" in text

def compile_(javac, src, extra):
    out = Path(tempfile.mkdtemp())
    c = subprocess.run([str(javac), *extra, "-d", str(out), str(src)], capture_output=True, text=True)
    errs = [(int(m.group(1)), m.group(2)) for m in re.finditer(r"\.java:(\d+): error: (.*)", c.stderr)]
    return c.returncode, errs, c.stderr, out

def run(java, out, cls):
    r = subprocess.run([str(java), "-cp", str(out), cls], capture_output=True, text=True)
    return r.stdout.strip().splitlines()

def judge_neg(rc, errs, stderr, want, here):
    if rc == 0: return "FAIL", "compiled, but should be rejected"
    if not errs: return "FAIL", stderr.strip()[:200]
    at = errs[0][0]
    ok = at == here and any(l == here and want in m for l, m in errs)
    msg = next((m for l, m in errs if l == here and want in m), errs[0][1])
    return ("PASS" if ok else "FAIL"), (msg if ok else f"line {at} (want {here}): {errs[0][1]}")

def judge_pos(rc, errs, stderr, out, cls, expect, java):
    if rc != 0: return "FAIL", "does not compile: " + (errs[0][1] if errs else stderr.strip()[:200])
    got = run(java, out, cls)
    return ("PASS", "") if got == expect else ("FAIL", f"printed {got}, expected {expect}")

SIGNED_SEMANTICS = ("signed semantics", "arithmetic shift", "sign-extends", "string concatenation shows the signed value")

# ---------------------------------------------------------------- 1. the Tessera javac on the original suite
tess_rows = []
T = ROOT / "tessera-eval/javac-tests"
have_tjdk = (TJDK / "bin/javac").exists()
for kind in ("pos", "neg"):
    for src in sorted((T / kind).glob("*.java")):
        what, expect, want, here, _ = meta(src.read_text())
        if not have_tjdk:
            tess_rows.append([src.stem, kind, what, "NOT RUN", "no Tessera JDK at " + str(TJDK)]); continue
        rc, errs, stderr, out = compile_(TJDK / "bin/javac", src, [])
        res, detail = (judge_pos(rc, errs, stderr, out, src.stem, expect, STOCK / "bin/java") if kind == "pos"
                       else judge_neg(rc, errs, stderr, want, here))
        tess_rows.append([src.stem, kind, what, res, detail])
        print(f"tessera {src.stem} {res:4} {what[:50]:50} {detail[:80]}")
with open(OUT / "javac-tests-tessera.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["test", "kind", "what", "result", "detail"]); w.writerows(tess_rows)

# ---------------------------------------------------------------- 2, 3. the stock-syntax suite
S = ROOT / "tessera-eval/javac-tests-stock"
stock_rows = []
for kind in ("pos", "neg"):
    for src in sorted((S / kind).glob("*.java")):
        what, expect, want, here, lowered = meta(src.read_text())
        for mode in ("check", "lite"):
            rc, errs, stderr, out = compile_(STOCK / "bin/javac", src, [*EXPORTS, "-processorpath", PLUGINS, *MODES[mode]])
            if kind == "neg":
                res, detail = judge_neg(rc, errs, stderr, want, here)
                expected = "rejected"
            elif mode == "check" and lowered:
                expected = "rejected (signed Java semantics)"
                if rc == 0: res, detail = "FAIL", "compiled; the checker should reject it"
                elif all(any(s in m for s in SIGNED_SEMANTICS) for _, m in errs) and errs:
                    res, detail = "PASS", f"{len(errs)} error(s): {errs[0][1]}"
                else: res, detail = "FAIL", "; ".join(m for _, m in errs)[:200]
            else:
                expected = "prints " + " | ".join(expect)
                res, detail = judge_pos(rc, errs, stderr, out, src.stem, expect, STOCK / "bin/java")
            plain = ""
            if kind == "pos" and mode == "check" and lowered:
                rc0, errs0, _, out0 = compile_(STOCK / "bin/javac", src, [])
                plain = ("does not compile" if rc0 else " | ".join(run(STOCK / "bin/java", out0, src.stem)))
            stock_rows.append([src.stem, kind, what, mode, "yes" if lowered else "no", expected, res, detail, plain])
            print(f"stock {src.stem} {mode:5} {res:4} {what[:45]:45} {detail[:70]}" + (f"  [no plugin: {plain[:40]}]" if plain else ""))
with open(OUT / "javac-tests-stock.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["test", "kind", "what", "mode", "lowered", "expected", "result", "detail", "stock_javac_no_plugin_prints"])
    w.writerows(stock_rows)

# ---------------------------------------------------------------- feature x mechanism table
def feature(tid):
    n = int(tid[1:])
    if tid[0] == "P":
        if n <= 8 or 30 <= n <= 44: return "unsigned int/long semantics"
        if n in (9, 10): return "ushort/ubyte"
        if 11 <= n <= 16: return "uint<N> widths"
        if n in (20, 21, 26): return "bit select and slices"
        if n in (22, 23): return "concatenation {a, b}"
        if n in (24, 25): return "bits<N>"
        if n in (50, 51): return "operators on classes"
        return "trailing blocks"
    if n in (8, 9): return "uint<N> widths"
    if n in (10, 11): return "bits<N>"
    if n in (12, 13): return "bit select and slices"
    if n == 19: return "concatenation {a, b}"
    if n == 20: return "ushort/ubyte"
    return "signed/unsigned mixing errors"

MECH = {
    "unsigned int/long semantics": ("internal API (TesseraSyntaxLite lowers); public API (TesseraCheck rejects the unlowered operators)", ""),
    "signed/unsigned mixing errors": ("public API (TesseraCheck)", ""),
    "ushort/ubyte": ("not done (the checker covers @Unsigned int and long only)", ""),
    "uint<N> widths": ("not possible by a plugin (new type syntax)", "stock javac 25 on P11: parse error: illegal start of type"),
    "bit select and slices": ("not possible by a plugin (new syntax)", "stock javac 25 on P20: parse error: ';' expected"),
    "concatenation {a, b}": ("not possible by a plugin (new syntax)", "stock javac 25 on P22: parse error: illegal start of type"),
    "bits<N>": ("not possible by a plugin (new type syntax)", "stock javac 25 on P24: parse error: illegal start of type"),
    "operators on classes": ("not possible by a plugin: a + b parses but attribution fails before a plugin sees attributed trees; x[i] / x[hi:lo] on a class do not parse",
                             "stock javac 25: P50 bad operand types for binary operator + (attribution); P51 ']' expected (parse)"),
    "trailing blocks": ("not possible by a plugin (new syntax)", "stock javac 25 on P52: parse error: ';' expected"),
    "unsigned literals (123u)": ("not possible by a plugin (new literal syntax); stock tests use hex/decimal int literals", ""),
    "automatic local names": ("internal API (TesseraSyntaxLite)",
                              "no javac-tests case; JUnit: SyntaxLiteTest.localsBecomeNamedWires + negative control (stock javac), "
                              "SyntaxTest.gcdWithOperatorsAndTrailingBlocks (Tessera javac)"),
}
not_ported = {}
for line in (S / "not-ported.txt").read_text().splitlines():
    tid, feat, why = line.split("\t")
    not_ported.setdefault(feature(tid), []).append(tid)
table = {}
for t, kind, what, res, detail in tess_rows:
    e = table.setdefault(feature(t), {"tj": [0, 0], "check": [0, 0], "lite": [0, 0]})
    e["tj"][1] += 1; e["tj"][0] += res == "PASS"
for t, kind, what, mode, lowered, expected, res, detail, plain in stock_rows:
    e = table.setdefault(feature(t), {"tj": [0, 0], "check": [0, 0], "lite": [0, 0]})
    e[mode][1] += 1; e[mode][0] += res == "PASS"
with open(OUT / "javac-features.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["feature", "plugin_mechanism", "tessera_javac_pass", "tessera_javac_tests", "stock_check_pass", "stock_check_tests",
                "stock_lite_pass", "stock_lite_tests", "not_ported", "note"])
    for feat, (mech, note) in MECH.items():
        e = table.get(feat, {"tj": [0, 0], "check": [0, 0], "lite": [0, 0]})
        w.writerow([feat, mech, *e["tj"], *e["check"], *e["lite"], " ".join(sorted(not_ported.get(feat, []))), note])
tp = sum(r[3] == "PASS" for r in tess_rows)
sp = {m: (sum(r[6] == "PASS" for r in stock_rows if r[3] == m), sum(r[3] == m for r in stock_rows)) for m in ("check", "lite")}
print(f"Tessera javac: {tp}/{len(tess_rows)} pass; stock check: {sp['check'][0]}/{sp['check'][1]}; stock lite: {sp['lite'][0]}/{sp['lite'][1]}")
print("wrote", OUT / "javac-tests-tessera.csv", OUT / "javac-tests-stock.csv", OUT / "javac-features.csv")
