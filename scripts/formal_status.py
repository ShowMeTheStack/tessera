#!/usr/bin/env python3
"""Status of the riscv-formal waves started by scripts/formal_wave.py (read-only and light). Run it in another terminal:

  python3 scripts/formal_status.py [<wave> ...]          one snapshot
  python3 scripts/formal_status.py --watch [<seconds>]   refresh every 30 s (Ctrl-C to quit)

Without wave names, every build/formal-<wave>/ with a wave.log is shown. Per wave: start, the time cap and the time
left, and per core the verdicts so far against the number of checks it has (from checks/makefile): PASS, FAIL,
vacuous (PREUNSAT), other errors, running, not started. Then the longest-running checks and the load.
$RF is the riscv-formal checkout (default ~/tessera-tools/riscv-formal)."""
import os, re, subprocess, sys, time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RF = Path(os.environ.get("RF", Path.home() / "tessera-tools/riscv-formal")) / "cores"


def waves():
    named = [a for a in sys.argv[1:] if not a.startswith("--") and not a.isdigit()]
    if named:
        return named
    return sorted(p.parent.name[len("formal-"):] for p in (ROOT / "build").glob("formal-*/wave.log"))


def checks_of(core):
    mk = RF / core / "checks" / "makefile"
    if not mk.exists():
        return None
    m = re.search(r"^all:(.*)$", mk.read_text(), re.M)
    return m.group(1).split() if m else None


def verdict(d):
    st = d / "status"
    if not st.exists():
        return "running" if (d / "config.sby").exists() or (d / "logfile.txt").exists() else "pending"
    s = st.read_text().split()[0] if st.read_text().strip() else "?"
    if s == "ERROR":
        log = d / "logfile.txt"
        if log.exists() and "PREUNSAT" in log.read_text(errors="replace"):
            return "vacuous"
    return s


def elapsed(d):
    """Seconds since the check started (its config.sby is written when sby starts it)."""
    f = d / "config.sby" if (d / "config.sby").exists() else d / "logfile.txt"
    return time.time() - f.stat().st_mtime if f.exists() else 0


def hms(s):
    s = int(max(s, 0))
    return f"{s // 3600}h{s % 3600 // 60:02d}m"


def wave(name, longest):
    w = ROOT / "build" / f"formal-{name}"
    log = w / "wave.log"
    if not log.exists():
        print(f"\n[{name}] not started")
        return
    text = log.read_text().splitlines()
    head = text[0] if text else ""
    m = re.search(r"cap ([\d.]+) h \(until ([\d-]+ [\d:]+)\)", head)
    start = datetime.strptime(head[:19], "%Y-%m-%d %H:%M:%S") if head else None
    until = datetime.strptime(m.group(2), "%Y-%m-%d %H:%M:%S") if m else None
    now = datetime.now()
    left = (until - now).total_seconds() if until else 0
    print(f"\n[{name}] started {start:%a %H:%M}, cap {m.group(1) if m else '?'} h until {until:%a %H:%M} "
          f"— elapsed {hms((now - start).total_seconds())}" + ("  (cap REMOVED: runs uncapped)" if "cap REMOVED" in log.read_text()
          else f", left {hms(left)}" + ("  (CAP REACHED)" if left <= 0 else "")))
    cores = re.findall(r"start (tessera_\S+)", "\n".join(text))
    done = dict(re.findall(r"done (tessera_\S+) in (\d+) s", "\n".join(text)))
    cut = set(re.findall(r"CUT (tessera_\S+)", "\n".join(text)))
    targets = None
    t = re.search(r"targets \[(.*?)\]", head)
    if t:
        targets = [x.strip(" '") for x in t.group(1).split(",")]
    allcores = re.search(r"wave \S+: (\d+) cores", head)
    print(f"  {'core':22} {'state':9} {'checks':>6} {'PASS':>5} {'FAIL':>5} {'vac':>4} {'err':>4} {'run':>4} {'todo':>5}")
    for c in cores:
        names = targets or checks_of(c) or []
        counts = {"PASS": 0, "FAIL": 0, "vacuous": 0, "err": 0, "running": 0, "pending": 0}
        fails = []
        for n in names:
            d = RF / c / "checks" / n
            v = verdict(d) if d.exists() else "pending"
            if v in ("PASS", "FAIL", "vacuous", "running", "pending"):
                counts[v] += 1
            else:
                counts["err"] += 1
            if v == "FAIL":
                fails.append(n)
            if v == "running":
                longest.append((elapsed(d), c, n))
        state = "done" if c in done else "CUT" if c in cut else "running"
        print(f"  {c:22} {state:9} {len(names):6} {counts['PASS']:5} {counts['FAIL']:5} {counts['vacuous']:4} "
              f"{counts['err']:4} {counts['running']:4} {counts['pending']:5}" + (f"   FAIL: {' '.join(fails)}" if fails else ""))
    if allcores and len(cores) < int(allcores.group(1)):
        print(f"  ... {int(allcores.group(1)) - len(cores)} more cores not started yet")


def snapshot():
    os.system("clear" if "--watch" in sys.argv else "true")
    print(f"riscv-formal waves, {datetime.now():%a %d %b %H:%M:%S}   load {os.getloadavg()[0]:.1f} "
          f"{os.getloadavg()[1]:.1f} {os.getloadavg()[2]:.1f}")
    longest = []
    for w in waves():
        wave(w, longest)
    solvers = subprocess.run(["pgrep", "-c", "-f", "yices-smt2|bitwuzla"], capture_output=True, text=True).stdout.strip()
    if not solvers:
        solvers = str(len(subprocess.run(["pgrep", "-f", "yices-smt2|bitwuzla"], capture_output=True, text=True).stdout.split()))
    print(f"\nsolver processes: {solvers}")
    if longest:
        print("longest-running checks:")
        for e, c, n in sorted(longest, reverse=True)[:6]:
            print(f"  {hms(e):>7}  {c} {n}")


if __name__ == "__main__":
    if "--watch" in sys.argv:
        i = sys.argv.index("--watch")
        every = int(sys.argv[i + 1]) if len(sys.argv) > i + 1 and sys.argv[i + 1].isdigit() else 30
        try:
            while True:
                snapshot()
                time.sleep(every)
        except KeyboardInterrupt:
            pass
    else:
        snapshot()
