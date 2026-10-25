#!/usr/bin/env bash
# Build riscv-arch-test 2.7.4 (rv32i_m/I) as flat binaries with their reference signatures.
# Each test produces <name>.bin and <name>.sig (begin/end signature addresses, hex).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${ARCH_TEST_SRC:-$ROOT/build/riscv-arch-test}"
OUT="$ROOT/tessera-riscv/src/test/resources/arch-test"
ENV="$ROOT/tessera-riscv/src/test/arch-env"
CC="${RISCV_PREFIX:-riscv64-elf-}gcc"
OBJCOPY="${RISCV_PREFIX:-riscv64-elf-}objcopy"
NM="${RISCV_PREFIX:-riscv64-elf-}nm"
if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch 2.7.4 https://github.com/riscv-non-isa/riscv-arch-test.git "$SRC"
fi
SUITE="$SRC/riscv-test-suite/rv32i_m/I"
mkdir -p "$OUT" "$ROOT/build/arch-test"
cp "$SRC/COPYING.BSD" "$OUT/LICENSE.riscv-arch-test"
echo "riscv-arch-test 2.7.4" > "$OUT/VERSION"
for f in "$SUITE"/src/*.S; do
  name="$(basename "$f" .S)"
  elf="$ROOT/build/arch-test/$name.elf"
  if ! "$CC" -march=rv32i -mabi=ilp32 -static -nostdlib -nostartfiles -DXLEN=32 -Wl,--no-warn-rwx-segments \
      -I"$ENV" -I"$SRC/riscv-test-env" -I"$SRC/riscv-test-env/p" -T"$ENV/link.ld" "$f" -o "$elf" 2> "$elf.log"; then
    echo "skip $name (does not assemble with this toolchain): $(grep -m1 Error "$elf.log")"
    continue
  fi
  "$OBJCOPY" -O binary "$elf" "$OUT/$name.bin"
  b=$("$NM" "$elf" | awk '/ begin_signature$/ {print $1}')
  e=$("$NM" "$elf" | awk '/ end_signature$/ {print $1}')
  echo "$b $e" > "$OUT/$name.sig"
  cp "$SUITE/references/$name.reference_output" "$OUT/$name.reference_output"
done
ls "$OUT"/*.bin | wc -l
