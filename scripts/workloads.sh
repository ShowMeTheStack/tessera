#!/usr/bin/env bash
# Workloads: Embench-IoT 1.0 and CoreMark as flat binaries for Tessera's cores
# (build/workloads/<name>.bin, plus <name>.map with the report buffer address for CoreMark).
# Needs riscv64-elf-gcc and picolibc built for rv32im/ilp32 (PICOLIBC, see README.md).
# Sources are fetched into $WL (default ~/tessera-tools/workloads) at pinned versions.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WL="${WL:-$HOME/tessera-tools/workloads}"
PICOLIBC="${PICOLIBC:-$WL/picolibc-install}"
SUP="$ROOT/tessera-riscv/src/test/workloads"
# ISA=rv32i builds the same programs without M (for RV32I-only baselines) into build/workloads-rv32i
ISA="${ISA:-rv32im}"
# MEM_KB=16 links for a 16 KiB memory (the single-cycle LUT-RAM core on the board) into build/workloads-16k
MEM_KB="${MEM_KB:-64}"
OUT="$ROOT/build/workloads$([ "$ISA" = rv32im ] || echo "-$ISA")$([ "$MEM_KB" = 64 ] || echo "-${MEM_KB}k")"
CC=riscv64-elf-gcc
OBJCOPY=riscv64-elf-objcopy
EMBENCH_REV=embench-1.0
COREMARK_REV=1f483d5b8316753a742cbf5590caf5bd0a4e4777
COREMARK_ITERATIONS="${COREMARK_ITERATIONS:-10}"

mkdir -p "$WL" "$OUT"
[ -d "$WL/embench-iot" ] || git clone -q https://github.com/embench/embench-iot.git "$WL/embench-iot"
(cd "$WL/embench-iot" && git checkout -q "$EMBENCH_REV")
[ -d "$WL/coremark" ] || git clone -q https://github.com/eembc/coremark.git "$WL/coremark"
(cd "$WL/coremark" && git checkout -q "$COREMARK_REV")

ARCH="-march=$ISA -mabi=ilp32"
CFLAGS="$ARCH -O2 -std=gnu17 -ffunction-sections -fdata-sections -fno-common -isystem $PICOLIBC/include"
mkdir -p "$OUT"
sed "s/LENGTH = 64K/LENGTH = ${MEM_KB}K/; s/__stack_top - 8K/__stack_top - $([ "$MEM_KB" -ge 64 ] && echo 8 || echo 2)K/" "$SUP/link.ld" > "$OUT/link.ld"
LDFLAGS="$ARCH -nostdlib -nostartfiles -static -Wl,--gc-sections -Wl,--no-warn-rwx-segments -T$OUT/link.ld"
LIBS="-L$PICOLIBC/lib/$ISA/ilp32 -lc -lm -lgcc"

link() { # name objects...
  local name=$1; shift
  $CC $LDFLAGS -o "$OUT/$name.elf" "$SUP/crt0.S" "$@" $LIBS -Wl,-Map="$OUT/$name.ldmap" || { echo "$name does not fit in ${MEM_KB} KiB"; return 0; }
  $OBJCOPY -O binary "$OUT/$name.elf" "$OUT/$name.bin"
  riscv64-elf-nm "$OUT/$name.elf" | awk '$3=="report"||$3=="__bss_end"||$3=="__heap_start"{print $3, $1}' > "$OUT/$name.map"
  local size=$(riscv64-elf-size -A "$OUT/$name.elf" | awk '$1==".text"||$1==".rodata"||$1==".data"||$1==".bss"{s+=$2} END{print s}')
  echo "$name $size" >> "$OUT/sizes.txt"
}

: > "$OUT/sizes.txt"
E="$WL/embench-iot"
for d in "$E"/src/*/; do
  b=$(basename "$d")
  # WARMUP_HEAT=0: the cores have no caches to warm; CPU_MHZ=1: Embench's reference scale
  $CC $CFLAGS -DCPU_MHZ=1 -DWARMUP_HEAT=0 -I"$E/support" -I"$SUP" -c "$SUP/boardsupport.c" -o "$OUT/bs.o"
  objs=("$OUT/bs.o")
  for f in "$E/support/main.c" "$E/support/beebsc.c" "$d"/*.c; do
    o="$OUT/$b-$(basename "$f" .c).o"
    $CC $CFLAGS -DCPU_MHZ=1 -DWARMUP_HEAT=0 -I"$E/support" -I"$d" -c "$f" -o "$o"
    objs+=("$o")
  done
  link "embench-$b" "${objs[@]}"
done
C="$WL/coremark"
objs=()
for f in "$C"/core_list_join.c "$C"/core_main.c "$C"/core_matrix.c "$C"/core_state.c "$C"/core_util.c "$SUP/coremark/core_portme.c"; do
  o="$OUT/cm-$(basename "$f" .c).o"
  $CC $CFLAGS -DITERATIONS="$COREMARK_ITERATIONS" -DPERFORMANCE_RUN=1 -DFLAGS_STR="\"-O2\"" -I"$C" -I"$SUP/coremark" -c "$f" -o "$o"
  objs+=("$o")
done
link coremark "${objs[@]}"
rm -f "$OUT"/*.o
(cd "$WL/embench-iot" && git rev-parse HEAD) > "$OUT/VERSION"
(cd "$WL/coremark" && git rev-parse HEAD) >> "$OUT/VERSION"
cat "$OUT/sizes.txt"
