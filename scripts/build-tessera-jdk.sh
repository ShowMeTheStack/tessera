#!/usr/bin/env bash
# Build the Tessera JDK: OpenJDK 25.0.4 with the Tessera javac extensions (javac-fork/tessera-javac.patch).
# Output: <dir>/build/<conf>/images/jdk. Needs a boot JDK 25 and (on macOS) the command-line tools;
# the Metal shader compiler is stubbed because a headless JDK does not need it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$HOME/tessera-jdk/jdk25-tessera}"
BOOT="${BOOT_JDK:-$HOME/.sdkman/candidates/java/25.0.4-tem}"
if [ ! -d "$DIR" ]; then
  git clone --depth 1 --branch jdk-25.0.4+7 https://github.com/openjdk/jdk25u.git "$DIR"
  (cd "$DIR" && git checkout -b tessera-syntax && git am "$ROOT/javac-fork/tessera-javac.patch")
fi
STUB="$DIR/build/metal-stub"
mkdir -p "$STUB"
cat > "$STUB/metal" <<'S'
#!/bin/sh
if [ "$1" = "--version" ]; then echo stub; exit 0; fi
out=""; while [ $# -gt 0 ]; do if [ "$1" = "-o" ]; then out="$2"; shift; fi; shift; done
[ -n "$out" ] && : > "$out"; exit 0
S
cp "$STUB/metal" "$STUB/metallib"; chmod +x "$STUB/metal" "$STUB/metallib"
cd "$DIR"
[ -f build/*/spec.gmk ] || bash configure METAL="$STUB/metal" METALLIB="$STUB/metallib" --with-boot-jdk="$BOOT" \
    --disable-warnings-as-errors --with-debug-level=release --with-native-debug-symbols=none --with-version-opt=tessera
make images
ls -d build/*/images/jdk
