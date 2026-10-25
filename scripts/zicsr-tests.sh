#!/usr/bin/env bash
# Build the machine-mode test programs (tessera-riscv/src/test/zicsr/*.S) as flat binaries.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tessera-riscv/src/test/zicsr"
OUT="$ROOT/tessera-riscv/src/test/resources/zicsr"
CC="${RISCV_PREFIX:-riscv64-elf-}gcc"
OBJCOPY="${RISCV_PREFIX:-riscv64-elf-}objcopy"
mkdir -p "$OUT" "$ROOT/build/zicsr"
for f in "$SRC"/*.S; do
  name="$(basename "$f" .S)"
  "$CC" -march=rv32im_zicsr -mabi=ilp32 -static -nostdlib -nostartfiles -Wl,--no-warn-rwx-segments \
      -T"$SRC/link.ld" "$f" -o "$ROOT/build/zicsr/$name.elf"
  "$OBJCOPY" -O binary "$ROOT/build/zicsr/$name.elf" "$OUT/$name.bin"
done
ls "$OUT"
