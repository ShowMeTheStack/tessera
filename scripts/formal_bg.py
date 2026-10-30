#!/usr/bin/env python3
"""Start riscv-formal checks in the background, each in its own process group, and record them.

  scripts/formal_bg.py <jobs.tsv> <core dir> <check> <engine> [<check> <engine> ...]

<engine> is a yosys-smtbmc solver (yices, bitwuzla, ...). For an engine other than the one in
checks/<check>.sby, a copy checks/<check>_<engine>.sby with that engine is run instead. Each run
is `sby -f <file>.sby` in <core dir>/checks; its PID, PGID, start time and log are appended to
<jobs.tsv>, so the run can later be stopped by its recorded group only (scripts/formal_ctl.py)."""
import os, re, subprocess, sys, time
from pathlib import Path

TOOLS = Path(os.environ.get("TESSERA_TOOLS", Path.home() / "tessera-tools"))
SBY = TOOLS / "sby-install/bin/sby"
if not SBY.exists():
    SBY = Path("sby")  # from PATH
# solver shims first on PATH (bitwuzla: Yosys 0.64's smtio passes the pre-0.9 command line)
SHIMS = Path(__file__).resolve().parent / "solver-shims"


def sby_for(checks: Path, check: str, engine: str) -> str:
    base = (checks / f"{check}.sby").read_text()
    current = re.search(r"\[engines\]\n(.*)\n", base).group(1).split()[-1]
    if engine == current:
        return check
    name = f"{check}_{engine}"
    (checks / f"{name}.sby").write_text(re.sub(r"(\[engines\]\n)smtbmc \S+", rf"\1smtbmc {engine}", base))
    return name


def main():
    jobs, core, rest = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:]
    checks = core / "checks"
    env = dict(os.environ, PATH=f"{SHIMS}:{TOOLS / 'sby-install/bin'}:{os.environ['PATH']}")
    jobs.parent.mkdir(parents=True, exist_ok=True)
    if not jobs.exists():
        jobs.write_text("start\tpid\tpgid\tcore\tcheck\tengine\tlog\n")
    for check, engine in zip(rest[0::2], rest[1::2]):
        name = sby_for(checks, check, engine)
        log = jobs.parent / f"{core.name}_{name}.log"
        out = open(log, "a")
        p = subprocess.Popen([str(SBY), "-f", f"{name}.sby"], cwd=checks, env=env, stdout=out, stderr=subprocess.STDOUT,
                             start_new_session=True)
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        with open(jobs, "a") as f:
            f.write(f"{stamp}\t{p.pid}\t{os.getpgid(p.pid)}\t{core.name}\t{name}\t{engine}\t{log}\n")
        print(stamp, p.pid, core.name, name, engine)


if __name__ == "__main__":
    main()
