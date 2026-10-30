#!/usr/bin/env python3
"""One riscv-formal campaign wave over already-exported cores, with a hard time cap.

  scripts/formal_wave.py <name> --cap-hours H --jobs J --parallel K [--targets a,b] [--resume] <core> ...

For each core (a directory under $RF/cores, exported by `:tessera-riscv:rvfiExport`): the previous checks/
directory is moved to $RF/archive/<name>/<core>-checks (its verdicts stay as evidence), the checks are
generated, and `make -k -jJ` runs in a session of its own; K cores run at once, in the order given.
Every make is recorded (start, pid = pgid, core) in build/formal-<name>/jobs.tsv. At start + H hours,
any make still running is ended by its recorded process group (SIGTERM, then SIGKILL), and its unfinished
checks stay as "running at cutoff". The log is build/formal-<name>/wave.log, and a summary per core and check
(verdict and seconds) goes to build/results/formal-<name>.csv. scripts/formal_status.py shows a wave's progress.

$RF is the riscv-formal checkout (default ~/tessera-tools/riscv-formal). sby is taken from
$TESSERA_TOOLS/sby-install/bin (default ~/tessera-tools) or PATH; scripts/solver-shims goes first on PATH."""
import os, shutil, signal, subprocess, sys, time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RF = Path(os.environ.get("RF", Path.home() / "tessera-tools/riscv-formal"))
TOOLS = Path(os.environ.get("TESSERA_TOOLS", Path.home() / "tessera-tools"))
SHIMS = ROOT / "scripts/solver-shims"


def prune(core):
    """Each finished check's model/ and src/ (about 250 MB a core, rebuilt from the core's SV by a rerun); the
    verdicts, logs and traces stay."""
    for st in (RF / "cores" / core / "checks").glob("*/status"):
        for sub in ("model", "src"):
            shutil.rmtree(st.parent / sub, ignore_errors=True)


def main():
    a = sys.argv[1:]
    name = a.pop(0)
    opt = lambda k, d: type(d)(a[a.index(k) + 1]) if k in a else d
    cap, jobs, par = opt("--cap-hours", 12.0), opt("--jobs", 2), opt("--parallel", 4)
    # --targets a,b: only these checks (make targets), e.g. the checks a configuration change added
    targets = [t for t in opt("--targets", "").split(",") if t]
    valued = {"--cap-hours", "--jobs", "--parallel", "--targets"}  # options that take a value; --resume takes none
    cores = [x for i, x in enumerate(a) if not x.startswith("--") and (i == 0 or a[i - 1] not in valued)]
    out = ROOT / f"build/formal-{name}"
    out.mkdir(parents=True, exist_ok=True)
    log = open(out / "wave.log", "a")
    say = lambda s: (print(s, flush=True), log.write(f"{datetime.now():%F %T} {s}\n"), log.flush())
    jobs_tsv = out / "jobs.tsv"
    if not jobs_tsv.exists():
        jobs_tsv.write_text("start\tpid\tcore\n")
    env = dict(os.environ, PATH=f"{SHIMS}:{TOOLS / 'sby-install/bin'}:{os.environ['PATH']}")
    start = time.time()
    deadline = start + cap * 3600
    say(f"wave {name}: {len(cores)} cores, targets {targets or 'all'}, cap {cap} h (until {datetime.fromtimestamp(deadline):%F %T}), -j{jobs} x {par}")
    todo, running, done = list(cores), {}, {}
    while todo or running:
        while todo and len(running) < par and time.time() < deadline:
            c = todo.pop(0)
            d = RF / "cores" / c
            # with --targets the other checks' verdicts stay where they are (only the targets are rerun); with --resume
            # every finished check stays (make reruns only checks without a status file: a wave stopped or cut resumes)
            if (d / "checks").exists() and not targets and "--resume" not in a:
                arch = RF / "archive" / name
                arch.mkdir(parents=True, exist_ok=True)
                dst = arch / f"{c}-checks"
                if dst.exists():
                    dst = arch / f"{c}-checks-{int(time.time())}"
                shutil.move(str(d / "checks"), dst)
            if not ("--resume" in a and (d / "checks" / "makefile").exists()):
                subprocess.run(["python3", "../../checks/genchecks.py"], cwd=d, capture_output=True, check=True)
            if "--resume" in a:
                # a check stopped before its verdict leaves its directory behind, and sby refuses to start a check
                # whose directory exists (make -k then skips it): remove those, so that they run again
                for cd in (d / "checks").glob("*/"):
                    if cd.is_dir() and not (cd / "status").exists() and (cd.parent / f"{cd.name}.sby").exists():
                        shutil.rmtree(cd)
            p = subprocess.Popen(["make", "-C", "checks", f"-j{jobs}", "-k"] + targets, cwd=d, env=env, start_new_session=True,
                                 stdout=open(d / "make.log", "w"), stderr=subprocess.STDOUT)
            running[c] = (p, time.time())
            with open(jobs_tsv, "a") as f:
                f.write(f"{datetime.now():%F %T}\t{p.pid}\t{c}\n")
            say(f"start {c} (make pid/pgid {p.pid})")
        for c, (p, t0) in list(running.items()):
            if p.poll() is not None:
                done[c] = time.time() - t0
                del running[c]
                say(f"done {c} in {done[c]:.0f} s (make exit {p.returncode})")
                prune(c)
        if time.time() >= deadline:
            for c, (p, t0) in running.items():
                for sig in (signal.SIGTERM, signal.SIGKILL):
                    try:
                        os.killpg(p.pid, sig)  # the recorded group: make, sby, yosys-smtbmc, solvers
                    except OSError:
                        break
                    time.sleep(5)
                p.wait()
                say(f"CUT {c} at the {cap} h cap after {time.time() - t0:.0f} s")
                prune(c)
            if todo:
                say("NOT STARTED (cap reached): " + " ".join(todo))
            break
        time.sleep(10)
    csv = ROOT / "build/results" / f"formal-{name}.csv"
    csv.parent.mkdir(parents=True, exist_ok=True)
    with open(csv, "w") as f:
        f.write("core,check,verdict,seconds\n")
        for c in cores:
            for st in sorted((RF / "cores" / c / "checks").glob("*/status")):
                words = st.read_text().split()
                verdict = words[0] if words else "?"
                secs = next((w for w in words[1:] if w.isdigit()), "")
                f.write(f"{c},{st.parent.name},{verdict},{secs}\n")
    say(f"summary -> {csv.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
