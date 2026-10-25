#!/usr/bin/env bash
# Build the RV32I and RV32M riscv-tests (rv32ui, rv32um) as flat binaries for Tessera's cores.
# Uses a minimal test environment (no CSRs): pass/fail is reported in gp (x3) at ECALL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/build/riscv-tests-src"
OUT="$ROOT/tessera-riscv/src/test/resources/riscv-tests"
ENV="$ROOT/tessera-riscv/src/test/riscv-env"
CC="${RISCV_PREFIX:-riscv64-elf-}gcc"
OBJCOPY="${RISCV_PREFIX:-riscv64-elf-}objcopy"

if [ ! -d "$SRC" ]; then
  git clone --depth 1 https://github.com/riscv-software-src/riscv-tests.git "$SRC"
fi
mkdir -p "$OUT"
cp "$SRC/LICENSE" "$OUT/LICENSE.riscv-tests"
(cd "$SRC" && git rev-parse HEAD) > "$OUT/VERSION"

SKIP="fence_i ma_data"
for suite in rv32ui rv32um; do
  for f in "$SRC"/isa/$suite/*.S; do
    name="$(basename "$f" .S)"
    case " $SKIP " in *" $name "*) continue ;; esac
    elf="$ROOT/build/riscv-tests-src/$suite-$name.elf"
    "$CC" -march=rv32im -mabi=ilp32 -static -nostdlib -nostartfiles \
        -I"$ENV" -I"$SRC/isa/macros/scalar" -T"$ENV/link.ld" "$f" -o "$elf"
    "$OBJCOPY" -O binary "$elf" "$OUT/$suite-$name.bin"
  done
done
ls "$OUT" | wc -l
