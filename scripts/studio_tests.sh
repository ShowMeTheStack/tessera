#!/usr/bin/env bash
# Tessera Studio: its JUnit tests and the end-to-end browser suite (tessera-eval/browser/studio-suite.mjs).
# Writes build/results/studio-unit-tests.csv and studio-browser-tests.csv (or into $OUT_DIR).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="${PORT:-18801}"
# Tessera needs Java 25; a JAVA_HOME from the shell may be older, so it is set here (override with STUDIO_JAVA_HOME)
export JAVA_HOME="${STUDIO_JAVA_HOME:-$HOME/.sdkman/candidates/java/25.0.4-tem}"
OUT_DIR="${OUT_DIR:-$ROOT/build/results}"
mkdir -p "$OUT_DIR"
cd "$ROOT"
./gradlew -q :tessera-studio:test --rerun-tasks :tessera-studio:installDist
python3 - "$ROOT" "$OUT_DIR" <<'PY'
import glob, re, sys, csv
root, out = sys.argv[1], sys.argv[2]
rows = []
for x in sorted(glob.glob(root + "/tessera-studio/build/test-results/test/TEST-*.xml")):
    t = open(x).read()
    for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)" time="([^"]+)"(/?)>(\s*<(failure|error))?', t):
        if m.group(2).endswith("CountProbe"): continue      # a probe that prints counts, not a test
        rows.append([m.group(2).split(".")[-1], m.group(1).rstrip("()"), "FAIL" if m.group(6) else "pass", m.group(3)])
with open(out + "/studio-unit-tests.csv", "w", newline="") as f:
    w = csv.writer(f); w.writerow(["class", "test", "result", "seconds"]); w.writerows(rows)
print(len(rows), "unit tests,", sum(r[2] == "pass" for r in rows), "pass")
PY
tessera-studio/build/install/tessera-studio/bin/tessera-studio --port "$PORT" --src examples/src/main/java > "$ROOT/build/studio-suite.log" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT
up=0
for i in $(seq 1 60); do curl -sf "http://127.0.0.1:$PORT/" > /dev/null && { up=1; break; }; sleep 0.5; done
[ "$up" = 1 ] || { echo "the studio did not start:"; cat "$ROOT/build/studio-suite.log"; exit 1; }
cd "$ROOT/tessera-eval/browser"
[ -d node_modules ] || { npm install --silent; npx playwright install chromium; }
node studio-suite.mjs "http://127.0.0.1:$PORT/" "$OUT_DIR/studio-browser-tests.csv"
