# Exceptions: ECALL, illegal instruction, misaligned load, misaligned jump, CSR write to a read-only CSR.
# The handler records mcause in t3..., mtval in a register file area and skips the faulting instruction.
    .section .text.init
    .globl _start
_start:
    la    t0, handler
    csrw  mtvec, t0
    li    s0, 0                 # number of traps taken
    ecall                       # cause 11
    mv    s1, a6                # s1 = 11
    mv    s2, a7                # s2 = 0 (mtval)
    .word 0x00000000            # illegal: cause 2, mtval = 0
    mv    s3, a6
    li    t1, 0x1001
    lw    t2, 2(t1)             # misaligned: cause 4, mtval = 0x1003
    mv    s4, a6
    mv    s5, a7
    csrw  mhartid, t1           # read-only: cause 2, mtval = the instruction
    mv    s6, a6
    mv    s7, a7
    la    t1, after
    addi  t1, t1, 2
    jalr  x0, 0(t1)             # misaligned target: cause 0, mtval = target
after:
    mv    s8, a6
    mv    s9, s0                # five traps
    csrr  s10, mepc
    ebreak

handler:
    csrr  a6, mcause
    csrr  a7, mtval
    addi  s0, s0, 1
    csrr  t6, mepc
    li    t5, 0
    bne   a6, t5, 1f            # misaligned fetch: return to the label instead of mepc + 4
    la    t6, after
    j     2f
1:  addi  t6, t6, 4
2:  csrw  mepc, t6
    mret
