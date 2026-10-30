#!/usr/bin/env python3
"""Bug injection. Each mutant is a source patch to one core; it is checked by
co-simulation (CoreMatrixTest on the cores it affects) and by riscv-formal (stopping at the
first failing check). Results: build/results/mutants.csv.

Usage: scripts/mutants.py [--cosim-only | --formal-only] [--jobs N] [mutant-id ...]
Needs riscv-formal in $RF (default ~/tessera-tools/riscv-formal) and SymbiYosys/yices on PATH.
The source tree is restored after every mutant, also on errors."""
import csv, glob, os, re, shutil, signal, subprocess, sys, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RV = "tessera-riscv/src/main/java/org/eninehq/tessera/riscv/"
RF = Path(os.environ.get("RF", Path.home() / "tessera-tools/riscv-formal"))
# MUTANTS_OUT: write elsewhere than build/results/mutants.csv
OUT = Path(os.environ["MUTANTS_OUT"]) if os.environ.get("MUTANTS_OUT") else ROOT / "build/results/mutants.csv"
# present while a mutant's patch is in the source tree: other builds should wait
LOCK = ROOT / "build/mutant.lock"
INORDER = ["C1a", "C1b", "C2", "C3", "C4", "C5", "C6", "C8", "C9", "C10"]
# the scheduled cores; REDIR: those with the plain redirect (no prediction, one lane); MULSTEP: those that finish
# multiplies in a step of their own (the others finish M results in "memory")
SCHED = ["C3", "C4", "C5", "C6", "C8", "C9", "C10"]
REDIR = ["C3", "C4", "C5", "C6"]
MULSTEP = ["C5", "C6", "C8", "C9", "C10"]

# (id, class, cores, file, old, new, description)
M = []
def mut(mid, cls, cores, f, old, new, what):
    M.append(dict(id=mid, cls=cls, cores=cores, file=f if f.startswith("tessera-") else RV + f, old=old, new=new, what=what))

# ---- C1a/C1b: SingleCycleCore (no forwarding, stalls or flushes to get wrong)
S = "SingleCycleCore.java"
mut("c1-trapwrite", "M4 trap writes rd", ["C1a", "C1b"], S,
    "when(eff.writesRd(), () -> regs.write(dec.rd().bits(4, 0), rdValue));",
    "when(dec.writesRd(), () -> regs.write(dec.rd().bits(4, 0), rdValue));", "rd written by a trapping instruction")
mut("c1-trapstore", "M5 trap stores", ["C1a", "C1b"], S,
    "dmemWrite(idx, merged, complete.and(dec.store()).and(eff.commits()));",
    "dmemWrite(idx, merged, complete.and(dec.store()));", "store by a trapping instruction")
mut("c1-target", "M6 trap target", ["C1a", "C1b"], S,
    "UInt nextPc = mux(eff.redirect(), eff.target(), ex.nextPc());",
    "UInt nextPc = ex.nextPc();", "traps and MRET continue at the next pc")
mut("c1-csrcommit", "M7 CSR update timing", ["C1a", "C1b"], S,
    "updateCsrs(csrs, eff.next(), complete);", "updateCsrs(csrs, eff.next(), run);", "CSRs updated every cycle, not when the instruction completes")
mut("c1-intr", "M8 interrupt marker", ["C1a", "C1b"], S,
    "intrNext.set(eff.interrupt());", "intrNext.set(Bool.of(false));", "rvfi_intr never set")
mut("c1b-phase", "M14 memory timing", ["C1b"], S,
    "Bool complete = (bram ? executing.and(memOp.not()).and(divWait.not()).or(inMemory) : divWait.not())",
    "Bool complete = (bram ? executing.and(dec.load().not()).and(divWait.not()).or(inMemory) : divWait.not())",
    "stores complete before their memory cycle (sub-word stores merge a stale word)")

# ---- shared by the in-order cores: the sequential divider in RvCore
mut("rvcore-divearly", "M9 divider", INORDER, "RvCore.java",
    "Hw.when(count.eq((d.altops() ? 4 : 32) - DIV_BITS_PER_CYCLE), () -> {",
    "Hw.when(count.eq((d.altops() ? 4 : 30) - DIV_BITS_PER_CYCLE), () -> {", "the sequential divider stops one step early")

# ---- shared ISA semantics: the ISS is built from the same source
mut("rv32i-sub", "M10 shared semantics", ["C5"], "Rv32i.java",
    "d.mux(sub, d.sub(a, opB), d.add(a, opB)),", "d.mux(sub, d.add(a, opB), d.add(a, opB)),", "SUB computes ADD (in the shared Rv32i, so also in the ISS)")
mut("csr-mscratch-drop", "M10 shared semantics", ["C5"], "Zicsr.java",
    "upd.apply((long) MSCRATCH, c.mscratch())", "c.mscratch()", "writes to mscratch are lost (in the shared Zicsr, so also in the ISS)")
mut("csr-mie-mask", "M10 shared semantics", ["C5"], "Zicsr.java",
    "d.and(value, d.k(0x888)), c.mie())", "d.and(value, d.k(0x880)), c.mie())", "mie.MSIE cannot be written (in the shared Zicsr, so also in the ISS)")

# ---- C2: FiveStageCore, hazards written by hand
F = "FiveStageCore.java"
mut("c2-noexfwd", "M1 forwarding", ["C2"], F,
    "for (var prod : new Producer[]{wb, mem, ex}) {", "for (var prod : new Producer[]{wb, mem}) {", "EX-to-ID forwarding removed")
mut("c2-nowbfwd", "M1 forwarding", ["C2"], F,
    "Producer wbFwd = bug == TestBug.NO_WB_FORWARD ? null : wb;", "Producer wbFwd = null;", "WB-to-ID forwarding removed")
mut("c2-noloaduse", "M2 stall", ["C2"], F,
    "if (bug != TestBug.NO_LOAD_USE_STALL) p.stall(waitEx.or(waitMem).and(p.valid()));",
    "if (bug != TestBug.NO_LOAD_USE_STALL) p.stall(waitMem.and(p.valid()));", "load-use (and M-use in EX) stall removed")
mut("c2-nomuse", "M2 stall", ["C2"], F,
    "Bool waitMem = hazard(mem, dec, rs1, rs2).and(mem.notReady().get());", "Bool waitMem = Bool.of(false);",
    "M-use stall for an M result in MEM removed")
mut("c2-noflush", "M3 flush", ["C2"], F,
    "p.flush(taken.or(haltHere));", "p.flush(haltHere);", "no flush after a taken branch, jump or trap")
mut("c2-trapwrite", "M4 trap writes rd", ["C2"], F,
    "writesOk = eff.writesRd();", "writesOk = dec.writesRd();", "rd written by a trapping instruction")
mut("c2-trapstore", "M5 trap stores", ["C2"], F,
    "Bool storing = dec.store().and(commits).and(p.valid()).and(p.advancing());",
    "Bool storing = dec.store().and(p.valid()).and(p.advancing());", "store by a trapping instruction")
mut("c2-target", "M6 trap target", ["C2"], F,
    "redirectTo = mux(eff.redirect(), eff.target(), exr.target());", "redirectTo = exr.target();", "traps and MRET go to the branch target")
mut("c2-csrcommit", "M7 CSR update timing", ["C2"], F,
    "updateCsrs(csrs, eff.next(), fire);", "updateCsrs(csrs, eff.next(), exValid);", "CSRs updated while EX is held")
mut("c2-intr", "M8 interrupt marker", ["C2"], F,
    "when(fire.and(eff.interrupt()), () -> handlerNext.set(true));", "", "rvfi_intr never set")
mut("c2-nobypass", "M11 BRAM store bypass", ["C2"], F,
    "UInt word = bram ? mux(lastValid.and(lastIdx.eq(idx)), lastWord, syncWord) : dmemRead(idx, Bool.of(true), false);",
    "UInt word = bram ? syncWord : dmemRead(idx, Bool.of(true), false);", "store-to-load bypass for block RAM removed")

# ---- C3-C6: ScheduledCore, mutants of the model (hazards are inferred)
C = "ScheduledCore.java"
mut("sched-nofwd", "M1 forwarding", SCHED, C,
    '                .resultAfter("execute", ALU, ALU_READY)\n', "", "model: no result declared after execute (the generator stalls instead)")
mut("sched-loadready", "M2 stall", SCHED, C,
    "c.put(ALU_READY, dec.load().not().and(dec.mulDiv().not()));", "c.put(ALU_READY, dec.mulDiv().not());",
    "model: loads declared ready after execute")
mut("sched-mready", "M2 stall", ["C3", "C4"], C,
    "forwarded a stage later\n                        c.put(MEM_FWD, loadOrAlu);\n                        c.put(MEM_READY, dec.mulDiv().not());",
    "forwarded a stage later\n                        c.put(MEM_FWD, loadOrAlu);\n                        c.put(MEM_READY, Bool.of(true));",
    "model: M results declared ready after memory")
mut("sched-mready-mul", "M2 stall", MULSTEP, C,
    "c.put(MEM_READY, dec.mulDiv().not());\n                    } else {", "c.put(MEM_READY, Bool.of(true));\n                    } else {",
    "model: M results declared ready after memory (the load/ALU value forwarded for them)")
mut("sched-latered", "M3 flush", REDIR, C,
    '.redirect("execute", REDIRECT, TARGET)', '.redirect("memory", REDIRECT, TARGET)', "model: redirect taken a step later")
mut("sched-trapwrite", "M4 trap writes rd", SCHED, C,
    "c.put(WRITES_OK, eff.writesRd());", "c.put(WRITES_OK, c.get(WRITES));", "rd written by a trapping instruction")
mut("sched-trapstore", "M5 trap stores", SCHED, C,
    "Bool storing = c.fire().and(dec.store()).and(c.get(COMMITS));", "Bool storing = c.fire().and(dec.store());",
    "store by a trapping instruction")
# TARGET is read only by redirect(), which the predicting and two-lane cores do not use (they resolve NEXT_PC)
mut("sched-target", "M6 trap target", REDIR, C,
    "c.put(TARGET, mux(eff.redirect(), eff.target(), ex.target()));", "c.put(TARGET, ex.target());", "traps and MRET go to the branch target")
mut("sched-csrcommit", "M7 CSR update timing", SCHED, C,
    "updateCsrs(csrs, eff.next(), c.fire());", "updateCsrs(csrs, eff.next(), c.valid());", "CSRs updated while execute is held")
mut("sched-intr", "M8 interrupt marker", SCHED, C,
    "when(c.fire().and(eff.interrupt()), () -> handlerNext.set(true));", "", "rvfi_intr never set")
mut("sched-nobypass", "M11 BRAM store bypass", SCHED, C,
    "UInt word = bram ? mux(lastValid.and(lastIdx.eq(idx)), lastWord, c.get(SYNC_WORD)) : dmemRead(idx, Bool.of(true), false);",
    "UInt word = bram ? c.get(SYNC_WORD) : dmemRead(idx, Bool.of(true), false);", "store-to-load bypass for block RAM removed")
mut("sched-retirevalid", "M12 retire on valid", SCHED, C,
    "Bool retires = c.fire().and(c.halted().not()).and(interrupted.not());", "Bool retires = c.valid().and(c.halted().not()).and(interrupted.not());", "retire reported on valid, not fire")

# ---- where the multiply finishes (the cores with a multiply-finish step)
MULV = MULSTEP
mut("mul-ready", "M15 multiply finish", MULV, C,
    "c.put(MD_READY, dec.mulDiv().and(isDiv.not()).not());", "c.put(MD_READY, Bool.of(true));",
    "the value handed on by memory is marked final for a multiply (forwarded before the multiply finishes)")
mut("mul-select", "M15 multiply finish", MULV, C,
    "Bool isMul = dec.mulDiv().and(dec.funct3().bit(2).not());", "Bool isMul = dec.mulDiv().and(dec.funct3().bit(1).not());",
    "the multiply result is selected on funct3 bit 1 instead of bit 2")
mut("gen-fwd-early", "M15 multiply finish", ["C5", "C6", "C8"], "tessera-pipeline/src/main/java/org/eninehq/tessera/pipeline/InOrderPipeline.java",
    "avail.add(new Avail(rp.forward() == Forward.NEXT_STAGE ? st + 1 : st,", "avail.add(new Avail(st,",
    "generator: NEXT_STAGE results forwarded from their own stage (correct values, longer path: expected to survive)")

# ---- branch prediction: model mutants on both predicting cores, generator mutants on the single-issue one
PREDV = ["C8", "C10"]
G = "tessera-pipeline/src/main/java/org/eninehq/tessera/pipeline/InOrderPipeline.java"
mut("pred-nocheck", "M16 prediction", PREDV, C,
    "return m.resolve(\"execute\", NEXT_PC, null, zicsr ? EVENT : null, dual && zicsr ? NEXT_PLAIN : null).halt(\"execute\", HALT);",
    "return m.halt(\"execute\", HALT);",
    "execute no longer checks the guess (only decode does, where it checks at all)")
mut("pred-young", "M16 prediction", ["C8"], G,
    "                next = mux(resolveRedirect.get(i).get(), resolveTarget.get(i).get(), next);\n            }",
    "            }\n            for (int i = resolves.size() - 1; i >= 0; i--) next = mux(resolveRedirect.get(i).get(), resolveTarget.get(i).get(), next);",
    "generator: the youngest item's redirect wins")
mut("pred-noflush", "M16 prediction", ["C8"], G,
    "                    resolveTarget.get(ri).set(next);\n                    p.flush(wrong);",
    "                    resolveTarget.get(ri).set(next);",
    "generator: a wrong guess redirects fetch but does not cancel younger items")
mut("pred-stale", "M16 prediction", ["C8"], G,
    "ctx.put(believed.get(ri + 1), mux(known, next, was));", "ctx.put(believed.get(ri + 1), was);",
    "generator: a corrected guess is not passed on (a decode guess that turns out wrong is never corrected)")
mut("pred-trainvalid", "M16 prediction", ["C8"], C,
    "when(c.fire().and(cf), () -> btb.write(idx, entry));", "when(c.valid().and(cf), () -> btb.write(idx, entry));",
    "BTB trained while execute is held (predictor state only: expected to survive)")
mut("pred-trainvalid2", "M16 prediction", ["C10"], C,
    "Bool en = c.fire().and(cf.or(c.get(PRED_TAKEN)));", "Bool en = c.valid().and(cf.or(c.get(PRED_TAKEN)));",
    "BTB trained while execute is held, two lanes (predictor state only: expected to survive)")
mut("pred-noforce", "M16 prediction", PREDV, C,
    "resolve(\"execute\", NEXT_PC, null, zicsr ? EVENT : null, dual && zicsr", "resolve(\"execute\", NEXT_PC, null, null, dual && zicsr",
    "a trap or interrupt whose handler is the guessed pc does not fetch again")
mut("pred-held", "M16 prediction", ["C8"], G,
    "Bool wrong = known.and(p.valid()).and(p.advancing()).and(next.ne(was).or(always));",
    "Bool wrong = known.and(p.valid()).and(next.ne(was).or(always));",
    "generator: a held stage resolves")
mut("pred-target", "M16 prediction", PREDV, C,
    "private static UInt btbTarget(UInt e) { return e.bits(31, 2).cat(UInt.lit(0, 2)); }",
    "private static UInt btbTarget(UInt e) { return e.bits(31, 2).cat(UInt.lit(2, 2)); }",
    "BTB targets misaligned by 2 bytes (every taken guess wrong: expected to survive, slower)")

# ---- dual issue
DUALV = ["C9", "C10"]
D = "tessera-pipeline/src/main/java/org/eninehq/tessera/pipeline/DualIssuePipeline.java"
mut("dual-raw", "M17 dual issue", DUALV, D,
    ".and(raw.not()).and(waw", ".and(waw", "generator: lane 1 issues beside lane 0 even when it reads lane 0's result")
mut("dual-waw", "M17 dual issue", DUALV, D,
    ".and(waw.not()).and(stall", ".and(stall", "generator: two instructions writing the same register issue together")
mut("dual-nokill", "M17 dual issue", DUALV, D,
    "                            c[1].laneValid = c[1].laneValid.and(wrong.not());\n", "",
    "generator: a redirect (or trap) in lane 0 does not cancel lane 1 beside it")
mut("dual-fwdorder", "M17 dual issue", DUALV, D,
    "for (int l = 1; l >= 0; l--)", "for (int l = 0; l <= 1; l++)",
    "generator: within a stage, lane 0's result is forwarded over lane 1's (the younger)")
mut("dual-leftover", "M17 dual issue", DUALV, D,
    "shiftPrev.set(mux(p.advancing(), splitNow, shiftPrev).and(deepFlush.not()));",
    "shiftPrev.set(mux(p.advancing(), splitNow, shiftPrev));",
    "generator: a left-over instruction is not cancelled by a flush from a later stage")
mut("dual-rvfiorder", "M17 dual issue", DUALV, "tessera-riscv/src/main/java/org/eninehq/tessera/riscv/RvCore.java",
    "rvfi1.order().set(order.addWrap(n0));", "rvfi1.order().set(order);\n        rvfiOrder.set(order.addWrap(n1));",
    "RVFI: lane 1's instruction reported before lane 0's (order swapped)")

# ---- symmetric lanes (loads and stores in lane 1, a true dual-port data memory) and the LVT register file
SYMV = ["C9s", "C10s", "C9s-rep", "C10s-rep"]
SPLITV, REPV, LVTV = ["C9s", "C10s"], ["C9s-rep", "C10s-rep"], ["C9-lvt", "C10-lvt"]
SC = "ScheduledCore.java"
mut("sym-nosplit", "M17 dual issue", SPLITV, SC,
    "if (lane1Memory == Lane1Memory.SPLIT) m = m.splitPairWhen(MEM_STORE, MEM_ACCESS);", "",
    "symmetric lanes: a store in lane 0 and a load or store in lane 1 issue together (no pair rule)")
mut("sym-noreplay", "M17 dual issue", REPV, SC,
    "                        replay = replay.or(same.named(\"lane1_same_word\"));\n", "",
    "symmetric lanes: lane 1 is not replayed when lane 0 stores to the word it accesses")
mut("sym-replaywrite", "M17 dual issue", REPV, SC,
    "ex.writesRd().and(replay.not()) : ex.writesRd()", "ex.writesRd() : ex.writesRd()",
    "symmetric lanes: a replayed lane-1 load still writes its register (a bug found during development)")
mut("sym-bypass1", "M17 dual issue", SYMV, SC,
    "for (int k = 0; k < 2; k++) word = mux(symLastValid", "for (int k = 0; k < 1; k++) word = mux(symLastValid",
    "symmetric lanes: the store bypass has lane 0's entry only")
mut("sym-noportwait", "M17 dual issue", SYMV, SC,
    "c.stall(sel.and(reads).and(c.validAtEntry())", "c.stall(Bool.of(false).and(reads).and(c.validAtEntry())",
    "symmetric lanes: a read in execute does not wait while its lane's port serves the store in memory")
mut("sym-bypasshold", "M17 dual issue", SYMV, SC,
    "        when(c.advancing(), () -> {\n            lv.set(storing);", "        when(Bool.of(true), () -> {\n            lv.set(storing);",
    "symmetric lanes: the bypass entries change while memory is held (a lane-1 load beside a lane-0 divide loses its hit)")
mut("lvt-notable", "M17 dual issue", LVTV, "tessera-stdlib/src/main/java/org/eninehq/tessera/stdlib/LiveValueTable.java",
    "table.write(p.addr(), UInt.lit(w, sel), enable(p));", "table.write(p.addr(), UInt.lit(0, sel), enable(p));",
    "LVT register file: the table never records lane 1 as the last writer (reads miss lane 1's writes)")
mut("lvt-order", "M17 dual issue", LVTV, "tessera-stdlib/src/main/java/org/eninehq/tessera/stdlib/LiveValueTable.java",
    "for (int w = 0; w < writes.size(); w++) {\n                var p = writes.get(w);\n                table.write(",
    "for (int w = writes.size() - 1; w >= 0; w--) {\n                var p = writes.get(w);\n                table.write(",
    "LVT register file: lane 0 wins a same-cycle write of one register (expected to survive: the pair check never issues WAW pairs)")

# ---- C7: OooCore
O = "OooCore.java"
mut("c7-wakeval", "M1 forwarding", ["C7"], O,
    "                    s1Val[k].set(busVal[bb]);", "                    s1Val[k].set(busVal[0]);", "wake-up of operand 1 always takes the ALU bus value")
mut("c7-latebus", "M2 stall", ["C7"], O,
    "Bool aluBus = aluIssue.and(late(aDec).not()).named(\"alu_bus\");", "Bool aluBus = aluIssue.named(\"alu_bus\");",
    "loads and CSR instructions broadcast their ALU result early")
mut("c7-noflush", "M3 flush", ["C7"], O,
    "flush = turn.and(eff.redirect().or(mispredict)).named(\"flush\");", "flush = turn.and(eff.redirect()).named(\"flush\");",
    "no flush after a mispredicted branch or jump")
mut("c7-trapwrite", "M4 trap writes rd", ["C7"], O,
    "cWrites = eff.writesRd();", "cWrites = hDec.writesRd();", "rd written by a trapping instruction")
mut("c7-trapstore", "M5 trap stores", ["C7"], O,
    "storeOk = eff.commits();", "storeOk = Bool.of(true);", "store by a trapping instruction")
mut("c7-target", "M6 trap target", ["C7"], O,
    "flushTarget = mux(eff.redirect(), eff.target(), hNext);", "flushTarget = hNext;", "traps and MRET continue at the next pc")
mut("c7-csrcommit", "M7 CSR update timing", ["C7"], O,
    "updateCsrs(csrs, eff.next(), turn);", "updateCsrs(csrs, eff.next(), ready);", "CSRs updated in every cycle the head is ready")
mut("c7-intr", "M8 interrupt marker", ["C7"], O,
    "when(turn.and(eff.interrupt()), () -> intrNext.set(true));", "", "rvfi_intr never set")
# the OoO multiply/divide unit is RvCore.mulDivUnit, shared by C7 and C11
mut("c7-divearly", "M9 divider", ["C7"], "tessera-riscv/src/main/java/org/eninehq/tessera/riscv/RvCore.java",
    "Bool mdBus = (d.altops() ? mdBusy : mdBusy.and(mdIsDiv.not().or(mdCount.eq(32)))).named(\"md_bus\");",
    "Bool mdBus = (d.altops() ? mdBusy : mdBusy.and(mdIsDiv.not().or(mdCount.eq(30)))).named(\"md_bus\");",
    "the divider delivers its result one step early")
mut("c7-rat", "M13 rename table", ["C7"], O,
    "when(commit.and(cWrites).and(cRd.eq(x)).and(ratTag[x].eq(head)), () -> ratBusy[x].set(false));",
    "when(commit.and(cWrites).and(cRd.eq(x)), () -> ratBusy[x].set(false));", "commit clears a rename that a younger instruction made")

# ---- C11: OooSpecCore (execute-time branch recovery)
X = "OooSpecCore.java"
mut("c11-rs-nosquash", "M18 lost squash", ["C11"], X,
    "when(recover.and(younger(sMask[k], resBit)), () -> sValid[k].set(false));", "",
    "a mispredict does not squash the younger reservation stations")
mut("c11-rob-nosquash", "M18 lost squash", ["C11"], X,
    "when(recover.and(younger(rMask[e], resBit)), () -> rValid[e].set(false));", "",
    "a mispredict does not invalidate the younger ROB entries (only the tail moves)")
mut("c11-md-nocancel", "M18 lost squash", ["C11"], "RvCore.java",
    "Bool cancel = squash.and(younger).named(\"md_cancel\");", "Bool cancel = Bool.of(false).named(\"md_cancel\");",
    "a mispredict does not cancel a younger multiply/divide")
mut("c11-md-cancelall", "M18 lost squash", ["C11"], "RvCore.java",
    "Bool cancel = squash.and(younger).named(\"md_cancel\");", "Bool cancel = squash.named(\"md_cancel\");",
    "a mispredict cancels the multiply/divide even when it is older than the branch")
mut("c11-cp-neighbour", "M19 checkpoint restore", ["C11"], X,
    "Bool cBusy = pickB(aCp, busyCol);\n            UInt cTag = pick(aCp, tagCol);",
    "Bool cBusy = pickB(aCp.addWrap(1), busyCol);\n            UInt cTag = pick(aCp.addWrap(1), tagCol);",
    "the RAT is restored from the neighbouring checkpoint (index off by one)")
mut("c11-tail-branch", "M19 checkpoint restore", ["C11"], X,
    "tail.set(aTag.addWrap(1));", "tail.set(aTag);", "the ROB tail is restored to the branch, not the branch + 1 (off by one)")
mut("c11-cp-before-rename", "M19 checkpoint restore", ["C11"], X,
    "cpBusy[cc][r].set(ratBusy[r].or(own));\n                    cpTag[cc][r].set(mux(own, tail, ratTag[r]));",
    "cpBusy[cc][r].set(ratBusy[r]);\n                    cpTag[cc][r].set(ratTag[r]);",
    "the checkpoint is the RAT before the branch's own rename (a JALR's link register is lost: one instruction early)")
mut("c11-restore-stale", "M19 checkpoint restore", ["C11"], X,
    "ratBusy[x].set(cBusy.and(inFlight));", "ratBusy[x].set(cBusy);",
    "the restored RAT keeps renames by instructions that committed after the checkpoint")
mut("c11-mask-noclear", "M20 branch mask", ["C11"], X,
    "                for (int i = 0; i < ROB; i++) rMask[i][b].set(false);\n                for (int j = 0; j < RS; j++) sMask[j][b].set(false);\n",
    "", "a correctly predicted branch frees its checkpoint but leaves its bit in the ROB and RS masks")
mut("c11-cp-noyounger", "M18 lost squash", ["C11"], X,
    "when(recover.and(resBit[c].or(younger(cpMask[c], resBit))), () -> cpValid[cc].set(false));",
    "when(recover.and(resBit[c]), () -> cpValid[cc].set(false));",
    "a mispredict frees its own checkpoint but not those of the younger (squashed) branches")
# the load/store queue (stage 2)
mut("c11-lq-nosquash", "M18 lost squash", ["C11"], X,
    "when(recover.and(younger(lMask[l], resBit)), () -> lValid[ll].set(false));", "",
    "a mispredict does not squash the younger loads in the load queue")
mut("c11-sq-nosquash", "M18 lost squash", ["C11"], X,
    "when(recover.and(younger(qMask[q], resBit)), () -> qValid[qq].set(false));", "",
    "a mispredict does not squash the younger stores in the store queue")
mut("c11-fwd-partial", "M21 forwarding", ["C11"], X,
    "covered = covered.or(youngest.and(covers));", "covered = covered.or(youngest);",
    "a store forwards to a load even if it writes only some of the bytes the load reads")
mut("c11-fwd-oldest", "M21 forwarding", ["C11"], X,
    "youngest = youngest.and(match[o].and(qAge[o].gt(qAge[q])).not());", "youngest = youngest.and(match[o].and(qAge[o].lt(qAge[q])).not());",
    "the oldest older store to the word forwards, not the youngest")
mut("c11-ld-unknown", "M22 memory order", ["C11"], X,
    "unknown = unknown.or(older.and(qAddrOk[q].not()));", "",
    "a load goes before an older store's address is known")
mut("c11-port-shared", "M22 memory order", ["C11"], X,
    "return new Words(w, w, commitEn.not());", "return new Words(w, w, Bool.of(true));",
    "a load is granted the read port in a cycle the commit reads it")

# C11-tdp: the true dual-port data memory (port A loads, port B the commit)
mut("c11tdp-store-early", "M22 memory order", ["C11-tdp"], X,
    "UInt b = core.dmemPort(1, commitIdx, commitEn.or(we), we, wdata);",
    "UInt b = core.dmemPort(1, commitIdx, commitEn.or(we), commitEn.or(we), wdata);",
    "port B also writes in the cycle a byte or halfword store reads its word, before it commits (a stale merge)")
mut("c11tdp-load-portb", "M22 memory order", ["C11-tdp"], X,
    "return new Words(b, a, Bool.of(true));", "return new Words(b, b, Bool.of(true));",
    "speculative loads take their word from port B (the commit's) instead of port A")
mut("c11-sq-early-free", "M22 memory order", ["C11", "C11-tdp"], X,
    "when(commit.and(qValid[q]).and(qTag[q].eq(head)), () -> qValid[qq].set(false));",
    "when(ready.and(qValid[q]).and(qTag[q].eq(head)), () -> qValid[qq].set(false));",
    "a store leaves the store queue when it is ready at the head, before it writes (a later load reads the old word)")

def sh(cmd, **kw):
    return subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, **kw)


def apply(m):
    p = ROOT / m["file"]
    src = p.read_text()
    n = src.count(m["old"])
    if n != 1:
        raise SystemExit(f"{m['id']}: snippet found {n} times in {m['file']}")
    p.write_text(src.replace(m["old"], m["new"]))
    return src


def cosim(m):
    """CoreMatrixTest on the mutant's cores: which suites fail (empty = survived) and CPI on riscv-tests."""
    shutil.rmtree(ROOT / "tessera-riscv/build/test-results/test", ignore_errors=True)
    r = sh(["./gradlew", "-q", "--no-build-cache", ":tessera-riscv:cleanTest", ":tessera-riscv:test",
            "--tests", "*CoreMatrixTest", "-Pcores=" + ",".join(m["cores"])], timeout=3600)
    xml = "".join(Path(f).read_text() for f in glob.glob(str(ROOT / "tessera-riscv/build/test-results/test/*CoreMatrix*.xml")))
    if not xml:
        return {"error": (r.stdout + r.stderr)[-400:]}
    out = {}
    for c in m["cores"]:
        # the method name is not in the display name: count failures per suite by display pattern
        suites = set()
        for t in re.finditer(r'<testcase name="([^"]+)" classname="[^"]+" time="[^"]+">\s*<failure', xml):
            name = t.group(1)
            if not re.search(r"\b" + c + r"\b", name):
                continue
            if " branches " in name: suites.add("branches")
            elif " memory " in name: suites.add("memory")
            elif "seed" in name: suites.add("random")
            elif "rv32u" in name: suites.add("riscv-tests")
            elif re.search(r"-01\b", name): suites.add("arch-test")
            elif name.endswith(" zicsr"): suites.add("zicsr")
            elif name.endswith(" directed"): suites.add("directed")
            elif name.endswith(" lint"): suites.add("lint")
            else: suites.add("other")
        cpi = re.search(r"MATRIX " + c + r" riscv-tests: \d+ cycles, \d+ retired, CPI ([\d.]+)", xml)
        out[c] = (sorted(suites), cpi.group(1) if cpi else "")
    return out


def export(m, core):
    """Export the mutated core for riscv-formal as cores/mut_<id>_<core>; returns the directory."""
    target = "tessera_" + core.lower().replace("-", "_")
    name = f"mut_{m['id']}_{core.lower()}".replace("-", "_")
    tmp = Path(tempfile.mkdtemp())
    r = sh(["./gradlew", "-q", ":tessera-riscv:rvfiExport", f"-PrvfiCores={tmp}", f"-PrvfiOnly={target}"], timeout=1800)
    src = tmp / target
    if not (src / "wrapper.sv").exists():
        # the mutated core does not elaborate: rejected before any formal check runs
        msg = re.search(r"error: ([^\n]*)", r.stdout + r.stderr)
        return "REJECTED at elaboration: " + (msg.group(1)[:160] if msg else (r.stdout + r.stderr).replace("\n", " ")[-160:])
    dst = RF / "cores" / name
    shutil.rmtree(dst, ignore_errors=True)
    dst.mkdir(parents=True)
    shutil.copy(src / "wrapper.sv", dst / "wrapper.sv")
    shutil.copy(src / "checks.cfg", dst / "checks.cfg")
    shutil.copy(src / (target + ".sv"), dst / (name + ".sv"))
    shutil.rmtree(tmp, ignore_errors=True)
    return dst


def formal(dst, jobs, timeout):
    """riscv-formal on an exported mutant: the first failing check, or PASS if all pass (or TIMEOUT)."""
    if isinstance(dst, str):
        return dst
    subprocess.run(["python3", "../../checks/genchecks.py"], cwd=dst, capture_output=True)
    # sby from $TESSERA_TOOLS/sby-install/bin (default ~/tessera-tools) or PATH, as in formal_bg.py; the solver shims go first
    tools = Path(os.environ.get("TESSERA_TOOLS", Path.home() / "tessera-tools"))
    env = dict(os.environ, PATH=f"{ROOT / 'scripts/solver-shims'}:{tools / 'sby-install/bin'}:{os.environ['PATH']}")
    proc = subprocess.Popen(["make", "-C", "checks", f"-j{jobs}", "-k"], cwd=dst, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, start_new_session=True, env=env)
    start = time.time()
    result = None
    while result is None:
        time.sleep(5)
        fails = [Path(s).parent.name for s in glob.glob(str(dst / "checks/*/status")) if Path(s).read_text().startswith("FAIL")]
        if fails:
            result = "FAIL " + sorted(fails)[0] + f" ({time.time() - start:.0f}s)"
        elif proc.poll() is not None:
            st = [Path(s).read_text().split()[0] for s in glob.glob(str(dst / "checks/*/status"))]
            # no verdict at all means the checks never ran (no sby, a broken export): not a pass
            result = (f"PASS {st.count('PASS')}/{len(st)} ({time.time() - start:.0f}s)" if st
                      else f"ERROR no check ran (make exit {proc.returncode})")
        elif time.time() - start > timeout:
            st = [Path(s).read_text().split()[0] for s in glob.glob(str(dst / "checks/*/status"))]
            result = f"TIMEOUT {st.count('PASS')} passed after {timeout}s"
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)  # the whole group: make, sby, yosys-smtbmc, yices
        except OSError:
            break  # already gone
        time.sleep(2)
    proc.wait()
    # the verdicts, logs and traces stay; each check's model/ and src/ (about 250 MB per core, rebuilt from the core's
    # SystemVerilog by a rerun) go, or a campaign of many mutant-core pairs fills the disk
    for d in glob.glob(str(dst / "checks/*/model")) + glob.glob(str(dst / "checks/*/src")):
        shutil.rmtree(d, ignore_errors=True)
    return result


def main():
    args = sys.argv[1:]
    do_cosim = "--formal-only" not in args
    do_formal = "--cosim-only" not in args
    jobs = int(args[args.index("--jobs") + 1]) if "--jobs" in args else 3
    ids = [a for a in args if not a.startswith("--") and not a.isdigit()]
    rows = {}
    if OUT.exists():
        with open(OUT) as f:
            for row in csv.DictReader(f):
                rows[(row["mutant"], row["core"])] = row
    only = [c for c in os.environ.get("MUTANT_CORES", "").split(",") if c]
    for m in M:
        if ids and m["id"] not in ids:
            continue
        if only:
            m = dict(m, cores=[c for c in m["cores"] if c in only])
            if not m["cores"]:
                continue
        def has_formal(c):  # a verdict, not an error or a run in which no check ran
            v = rows.get((m["id"], c), {}).get("formal", "")
            return v and not v.startswith(("ERROR", "PASS 0/0"))
        if do_formal and not do_cosim and "--redo" not in args and all(has_formal(c) for c in m["cores"]):
            continue  # resume: already has formal results
        LOCK.write_text(m["id"])
        original = apply(m)
        try:
            cs = cosim(m) if do_cosim else {}
            exported = {c: export(m, c) for c in m["cores"]} if do_formal else {}
        finally:
            # the source is restored before the (long) formal runs, so other builds can proceed
            (ROOT / m["file"]).write_text(original)
            LOCK.unlink(missing_ok=True)
        for core in m["cores"]:
            key = (m["id"], core)
            row = rows.get(key, {"mutant": m["id"], "class": m["cls"], "core": core, "what": m["what"]})
            if do_cosim:
                if "error" in cs:
                    row["cosim"] = "ERROR " + cs["error"].replace("\n", " ")[:200]
                else:
                    suites, cpi = cs[core]
                    row["cosim"] = "caught: " + " ".join(suites) if suites else "survived"
                    row["cpi_riscv_tests"] = cpi
            if do_formal:
                row["formal"] = formal(exported[core], jobs, 3600 if core in ("C7", "C11") else 1800)
            rows[key] = row
            print(m["id"], core, row.get("cosim", ""), "|", row.get("formal", ""), flush=True)
            save(rows)


def save(rows):
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["mutant", "class", "core", "what", "cosim", "cpi_riscv_tests", "formal"])
        w.writeheader()
        for r in rows.values():
            w.writerow({k: r.get(k, "") for k in w.fieldnames})


if __name__ == "__main__":
    main()
