#!/usr/bin/env python3
"""Pause, resume, stop or list the background riscv-formal runs recorded by formal_bg.py.

  scripts/formal_ctl.py <jobs.tsv> status|stop|cont|term [<check> ...]

Each run is found by the sby PID recorded in <jobs.tsv>, never by a name pattern. sby puts each engine in a
process group of its own, so the signal goes to the recorded PID and every descendant of it (found from
the process table by parent PID). A recorded PID whose command is no longer that sby run is skipped, so a
reused PID is never signalled. `stop`/`cont` pause and resume (SIGSTOP/SIGCONT), for idle-machine timing
runs; `term` ends the runs (SIGTERM). With check names, only those rows."""
import os, signal, subprocess, sys
from pathlib import Path

SIGNALS = {"stop": signal.SIGSTOP, "cont": signal.SIGCONT, "term": signal.SIGTERM}


def table():
    out = subprocess.run(["ps", "-A", "-o", "pid=,ppid=,stat=,etime=,rss=,command="], capture_output=True, text=True).stdout
    procs = {}
    for line in out.splitlines():
        pid, ppid, stat, etime, rss, cmd = line.split(None, 5)
        procs[int(pid)] = (int(ppid), stat, etime, int(rss), cmd)
    return procs


def descendants(procs, root):
    kids, todo = [], [root]
    while todo:
        p = todo.pop()
        for pid, (ppid, *_) in procs.items():
            if ppid == p:
                kids.append(pid)
                todo.append(pid)
    return kids


def main():
    jobs, action, only = Path(sys.argv[1]), sys.argv[2], set(sys.argv[3:])
    rows = [l.split("\t") for l in jobs.read_text().splitlines()[1:] if l.strip()]
    procs = table()
    for start, pid, pgid, core, check, engine, log in rows:
        if only and check not in only:
            continue
        pid = int(pid)
        root = procs.get(pid)
        if root is None or "sby" not in root[4] or f"{check}.sby" not in root[4]:
            print(f"{core} {check}: not running (pid {pid})")
            continue
        tree = [pid] + descendants(procs, pid)
        if action == "status":
            solver = [p for p in tree if any(s in procs[p][4] for s in ("yices", "bitwuzla", "boolector", "z3"))]
            info = ", ".join(f"{procs[p][4].split()[0].rsplit('/', 1)[-1]} {procs[p][1]} {procs[p][2]} {procs[p][3] // 1024} MB" for p in solver)
            print(f"{core} {check}: sby {pid} up {root[2]}; {info or 'no solver yet'}")
            continue
        # children first, so a stopped parent cannot respawn anything in between
        for p in reversed(tree):
            try:
                os.kill(p, SIGNALS[action])
            except ProcessLookupError:
                pass
        print(f"{core} {check}: {action} sent to {len(tree)} processes (root {pid})")


if __name__ == "__main__":
    main()
