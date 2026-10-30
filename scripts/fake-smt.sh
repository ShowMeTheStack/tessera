#!/bin/sh
# A stand-in SMT solver for SmtHashes (./gradlew :tessera-riscv:smtHashes): reads SMT-LIB from stdin and answers "unsat" to
# every (check-sat), so the formal back end sends its whole problem without anything being solved.
while IFS= read -r line; do
  case "$line" in
    *"(check-sat)"*) echo unsat ;;
    *"(exit)"*) exit 0 ;;
  esac
done
