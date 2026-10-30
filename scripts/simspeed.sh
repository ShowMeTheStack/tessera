#!/bin/bash
# Simulator-speed measurement, for an IDLE machine (run from the repository root).
#
#   scripts/simspeed.sh [--out DIR] [--base COMMIT] [--cores C5,C7,C9] [--rounds 5]
#                       [--iterations 1000000] [--batch-cycles 20000] [--taskset CPUS]
#                       [--skip-allcores] [--skip-base] [--skip-ab] [--skip-batch]
#                       [--skip-interp] [--skip-parallel]
#
# --base is the commit to compare with (default 90e62bd, the first release, before the simulator's code
# generation and class sharing); it is checked out as a worktree in build/simspeed-base.
#
# Writes into DIR (default build/results/simspeed):
#   simspeed.csv            SimSpeedAllCores -Pbench on this checkout: every validation core. compiled_hz is
#                           timed after a warm-up of the SAME (shared) classes; the extra columns
#                           compiled_hz_fresh_classes and interpreter_hz_no_warmup are the older way of measuring.
#   simspeed-base.csv       SimSpeedAllCores from the base commit (old simulator, old warm-up), for the
#                           old-vs-new table. Never mix its numbers with simspeed.csv's.
#   sim-opt.csv             per-optimisation A/B of the compiled simulator: one JVM per run, interleaved,
#   sim-opt-jvm.csv         configs base-fresh (base commit), alloff-fresh, alloff-shared (class sharing only),
#                           only-<step> for each code-generation step, new (all on); see scripts/sim_opt_ab.py.
#                           sim-opt-jvm.csv has per-JVM instruction counts when perf is usable (Linux + sudo).
#   batch-opt.csv(+ -jvm)   the same for BatchSim: base, alloff, only-transpose, only-compile (generated gates),
#                           compile-locals (generated gates with locals), new (all three).
#   interp-opt.csv(+ -jvm)  the same for the interpreter: base, nodecode (the old loop), new.
#   parallel.csv(+ -jvm)    the experimental ParallelSim: compiled vs parallel2/parallel4 on C7 and on MultiCore
#                           designs 4xC7 and 8xC5 (N independent copies); wall time is what matters here.
#   simspeed-env.txt        machine, JDK, commits, load averages before and after every step.
#   *.log                   the raw output of every step.
#
# The script refuses to start when the 1-minute load average is above MAX_LOAD (default 1.5); set MAX_LOAD to
# override (and say so wherever the numbers are used). PAUSE_PIDS="pid ..." are SIGSTOPped for the duration and
# SIGCONTed on exit (e.g. the PIDs of a long riscv-formal run); nothing is stopped otherwise.
#
# Time (est., idle Apple M-series / i9 class): all-cores ~2 min per tree; sim-opt ~60-90 min for 3 cores x 11
# configs x 5 rounds at 1,000,000 iterations; batch ~10-20 min.
set -euo pipefail

OUT=build/results/simspeed
BASE=90e62bd
CORES=C5,C7,C9
ROUNDS=5
ITER=1000000
BATCH_CYCLES=20000
TASKSET=""
DO_ALL=1; DO_BASE=1; DO_AB=1; DO_BATCH=1; DO_INTERP=1; DO_PAR=1
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT=$2; shift 2;;
    --base) BASE=$2; shift 2;;
    --cores) CORES=$2; shift 2;;
    --rounds) ROUNDS=$2; shift 2;;
    --iterations) ITER=$2; shift 2;;
    --batch-cycles) BATCH_CYCLES=$2; shift 2;;
    --taskset) TASKSET=$2; shift 2;;
    --skip-allcores) DO_ALL=0; shift;;
    --skip-base) DO_BASE=0; shift;;
    --skip-ab) DO_AB=0; shift;;
    --skip-batch) DO_BATCH=0; shift;;
    --skip-interp) DO_INTERP=0; shift;;
    --skip-parallel) DO_PAR=0; shift;;
    *) echo "unknown option $1" >&2; exit 2;;
  esac
done

ROOT=$(pwd)
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
ENV="$OUT/simspeed-env.txt"
BASEDIR="$ROOT/build/simspeed-base"
MAX_LOAD=${MAX_LOAD:-1.5}

load1() { if [ -r /proc/loadavg ]; then cut -d' ' -f1 /proc/loadavg; else sysctl -n vm.loadavg | awk '{print $2}'; fi; }
note() { echo "$(date '+%Y-%m-%d %H:%M:%S %Z') $*" | tee -a "$ENV"; }

if [ -n "${PAUSE_PIDS:-}" ]; then
  note "SIGSTOP $PAUSE_PIDS"
  trap 'kill -CONT $PAUSE_PIDS 2>/dev/null || true; note "SIGCONT $PAUSE_PIDS"' EXIT
  kill -STOP $PAUSE_PIDS
  sleep 5
fi

L=$(load1)
note "start; load1 $L; $(uptime)"
if awk -v l="$L" -v m="$MAX_LOAD" 'BEGIN { exit !(l > m) }'; then
  note "load1 $L > MAX_LOAD $MAX_LOAD: not idle, refusing to measure"; exit 3
fi
{
  echo "host: $(uname -a)"
  echo "cpus: $(getconf _NPROCESSORS_ONLN)"
  echo "commit: $(git rev-parse HEAD) $(git status --porcelain | wc -l | tr -d ' ') uncommitted paths"
  echo "base: $BASE"
  JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
  echo "java: $($JAVA -version 2>&1 | tr '\n' ' ')"
} >> "$ENV"

# the base tree: a detached worktree at $BASE plus the measurement driver (SimBench and the classpath task) only
if [ $DO_BASE = 1 ] || [ $DO_AB = 1 ] || [ $DO_BATCH = 1 ] || [ $DO_INTERP = 1 ]; then
  if [ ! -d "$BASEDIR" ]; then git worktree add --detach "$BASEDIR" "$BASE"; fi
  [ "$(git -C "$BASEDIR" rev-parse HEAD)" = "$(git rev-parse "$BASE")" ] || { note "base worktree is not at $BASE"; exit 4; }
  cp tessera-riscv/src/test/java/org/eninehq/tessera/riscv/SimBench.java tessera-riscv/src/test/java/org/eninehq/tessera/riscv/ParallelScope.java tessera-riscv/src/test/java/org/eninehq/tessera/riscv/MultiCore.java "$BASEDIR/tessera-riscv/src/test/java/org/eninehq/tessera/riscv/"
  grep -q simBenchClasspath "$BASEDIR/tessera-riscv/build.gradle.kts" || sed -n '/^\/\/ The test runtime classpath/,/^}/p' tessera-riscv/build.gradle.kts >> "$BASEDIR/tessera-riscv/build.gradle.kts"
  cp gradle.properties "$BASEDIR/gradle.properties"
  (cd "$BASEDIR" && ./gradlew -q :tessera-riscv:simBenchClasspath)
fi
./gradlew -q :tessera-riscv:simBenchClasspath

if [ $DO_ALL = 1 ]; then
  note "SimSpeedAllCores (this checkout); load1 $(load1)"
  ./gradlew :tessera-riscv:test --tests '*SimSpeedAllCores' --rerun -Pbench -PsimspeedOut="$OUT/simspeed.csv" > "$OUT/simspeed.log" 2>&1
  note "done; load1 $(load1)"
fi
if [ $DO_BASE = 1 ]; then
  note "SimSpeedAllCores (base $BASE); load1 $(load1)"
  (cd "$BASEDIR" && ./gradlew :tessera-riscv:test --tests '*SimSpeedAllCores' --rerun -Pbench -PsimspeedOut="$OUT/simspeed-base.csv") > "$OUT/simspeed-base.log" 2>&1
  note "done; load1 $(load1)"
fi

JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
PERF=""
if [ "$(uname)" = Linux ] && command -v perf > /dev/null && sudo -n true 2>/dev/null; then PERF=--perf; fi
TS=${TASKSET:+--taskset $TASKSET}
if [ $DO_AB = 1 ]; then
  # the steps on by default; ops and mux are off by default (measured worse) and are measured with -Dtessera.sim.enable
  OPTS="consts locals dce memread commit masks"
  ALL=$(echo $OPTS | tr ' ' ',')
  CFG=()
  for o in $OPTS; do
    d=""; for x in $OPTS; do [ $x = $o ] || d="$d,$x"; done
    CFG+=(--config "only-$o=new:-Dtessera.sim.disable=${d#,}")
  done
  CFG+=(--config "only-ops=new:-Dtessera.sim.disable=$ALL -Dtessera.sim.enable=ops")
  CFG+=(--config "only-mux=new:-Dtessera.sim.disable=$ALL -Dtessera.sim.enable=mux")
  note "sim-opt A/B: cores $CORES, $ITER iterations, $ROUNDS rounds; load1 $(load1)"
  python3 scripts/sim_opt_ab.py --java "$JAVA" --tree base="$BASEDIR" --tree new="$ROOT" \
    --config base-fresh=base \
    --config "alloff-fresh=new:-Dtessera.sim.disable=all -Dtessera.sim.shareClasses=false" \
    --config alloff-shared=new:-Dtessera.sim.disable=all \
    "${CFG[@]}" \
    --config new=new \
    --cores "$CORES" --iterations "$ITER" --reps 5 --warm 2 --rounds "$ROUNDS" $TS $PERF \
    --out "$OUT/sim-opt.csv" --jvm-out "$OUT/sim-opt-jvm.csv" > "$OUT/sim-opt.log" 2>&1
  note "done; load1 $(load1)"
fi
if [ $DO_BATCH = 1 ]; then
  note "batch A/B: cores $CORES, $BATCH_CYCLES cycles x 64 lanes, $ROUNDS rounds; load1 $(load1)"
  python3 scripts/sim_opt_ab.py --java "$JAVA" --tree base="$BASEDIR" --tree new="$ROOT" --backend batch \
    --config base=base \
    --config "alloff=new:-Dtessera.batch.compile=false -Dtessera.batch.transpose=false" \
    --config only-transpose=new:-Dtessera.batch.compile=false \
    --config "only-compile=new:-Dtessera.batch.transpose=false -Dtessera.batch.locals=false" \
    --config compile-locals=new:-Dtessera.batch.transpose=false \
    --config new=new \
    --cores "$CORES" --iterations "$BATCH_CYCLES" --reps 5 --warm 2 --rounds "$ROUNDS" $TS $PERF \
    --out "$OUT/batch-opt.csv" --jvm-out "$OUT/batch-opt-jvm.csv" > "$OUT/batch-opt.log" 2>&1
  note "done; load1 $(load1)"
fi
if [ $DO_INTERP = 1 ]; then
  note "interpreter A/B: cores $CORES, 20000 iterations, $ROUNDS rounds; load1 $(load1)"
  python3 scripts/sim_opt_ab.py --java "$JAVA" --tree base="$BASEDIR" --tree new="$ROOT" --backend interpreter \
    --config base=base --config nodecode=new:-Dtessera.interp.decode=false --config new=new \
    --cores "$CORES" --iterations 20000 --reps 5 --warm 2 --rounds "$ROUNDS" $TS $PERF \
    --out "$OUT/interp-opt.csv" --jvm-out "$OUT/interp-opt-jvm.csv" > "$OUT/interp-opt.log" 2>&1
  note "done; load1 $(load1)"
fi
if [ $DO_PAR = 1 ]; then
  # no --taskset here: the parallel workers need cores of their own
  note "parallel: C7, 4xC7, 8xC5; compiled, parallel2, parallel4; $ROUNDS rounds; load1 $(load1)"
  python3 scripts/sim_opt_ab.py --java "$JAVA" --heap 8g --tree new="$ROOT" \
    --config compiled@compiled=new --config par2@parallel2=new --config par4@parallel4=new \
    --cores C7,4xC7,8xC5 --iterations 100000 --reps 5 --warm 2 --rounds "$ROUNDS" \
    --out "$OUT/parallel.csv" > "$OUT/parallel.log" 2>&1
  note "done; load1 $(load1)"
fi
note "finished; $(uptime)"
