#!/bin/sh
# Run riscv-formal on the Tessera cores. Needs riscv-formal, SymbiYosys, Yosys and yices.
#   scripts/riscv-formal.sh <riscv-formal dir> [core ...]
# Cores: tessera_single tessera_five tessera_sched3 tessera_sched5 tessera_sched6 tessera_bram5 tessera_ooo
#        tessera_fpga5 tessera_single_zicsr (Zicsr, with CSR checks)
#        tessera_five_bug_wbfwd tessera_five_bug_loaduse (expected to FAIL)
set -e
RF=${1:?usage: riscv-formal.sh <riscv-formal dir> [core ...]}
shift || true
CORES=${*:-tessera_single tessera_five tessera_sched3 tessera_sched5 tessera_sched6 tessera_five_bug_wbfwd tessera_five_bug_loaduse}
HERE=$(cd "$(dirname "$0")/.." && pwd)
"$HERE/gradlew" -q -p "$HERE" :tessera-riscv:rvfiExport -PrvfiCores="$RF/cores" -PrvfiOnly="$(echo $CORES | tr ' ' ',')"
for c in $CORES; do
  (cd "$RF/cores/$c" && rm -rf checks && python3 ../../checks/genchecks.py > /dev/null && make -C checks -j"${JOBS:-8}" -k > make.log 2>&1 || true)
  pass=0; fail=0; other=0
  for d in "$RF/cores/$c/checks"/*/; do
    s=$(head -1 "$d/status" 2>/dev/null || echo NONE)
    case "$s" in PASS*) pass=$((pass+1));; FAIL*) fail=$((fail+1)); echo "  $c: FAIL $(basename "$d")";; *) other=$((other+1)); echo "  $c: $s $(basename "$d")";; esac
  done
  echo "$c: $pass passed, $fail failed, $other other"
done
