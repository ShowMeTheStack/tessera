#!/usr/bin/env bash
# jshell with Tessera on the class path and the usual imports, for trying hardware interactively:
#   scripts/jshell.sh
#   jshell> var pg = Playground.start(clk -> new Blinky(24), 8080, 10_000)   // then open http://127.0.0.1:8080/
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/gradlew" -q -p "$ROOT" :tessera-playground:jar :examples:jar :tessera-cli:jar >/dev/null
CP=$("$ROOT/gradlew" -q -p "$ROOT" :tessera-playground:printClasspath)
STARTUP="$(mktemp -t tessera-jshell).jsh"
cat > "$STARTUP" <<'J'
import org.eninehq.tessera.core.*;
import org.eninehq.tessera.core.Module;
import org.eninehq.tessera.sim.*;
import org.eninehq.tessera.stdlib.*;
import org.eninehq.tessera.examples.*;
import org.eninehq.tessera.playground.*;
import java.time.Duration;
System.out.println("Tessera: try  var pg = Playground.start(clk -> new Blinky(24), 8080, 10_000)");
J
exec jshell --class-path "$CP" -R--enable-native-access=ALL-UNNAMED --startup DEFAULT --startup "$STARTUP"
