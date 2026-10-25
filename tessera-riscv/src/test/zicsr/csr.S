# CSR instructions: every result lands in a register checked by the test (and by the ISS).
    .section .text.init
    .globl _start
_start:
    li    t0, 0x12345678
    csrw  mscratch, t0          # csrrw x0
    csrr  s0, mscratch          # s0 = 0x12345678
    li    t1, 0xF0
    csrrs s1, mscratch, t1      # s1 = old, set bits
    csrr  s2, mscratch          # s2 = 0x123456F8
    li    t1, 0x0F
    csrrc s3, mscratch, t1      # s3 = 0x123456F8
    csrr  s4, mscratch          # s4 = 0x123456F0
    csrrwi s5, mscratch, 21     # s5 = 0x123456F0, mscratch = 21
    csrrsi x0, mscratch, 8      # mscratch = 29
    csrrci x0, mscratch, 1      # mscratch = 28
    csrr  s6, mscratch          # s6 = 28
    csrr  s7, misa              # s7 = 0x40001100
    csrr  s8, mhartid           # s8 = 0
    csrr  a0, minstret
    nop
    nop
    csrr  a1, minstret          # a1 - a0 = 3
    sub   s9, a1, a0
    li    t0, 0xFFFFFFFF
    csrw  mtvec, t0
    csrr  s10, mtvec            # s10 = 0xFFFFFFFC (direct mode, aligned)
    csrw  mstatus, t0
    csrr  s11, mstatus          # s11 = 0x1888 (MIE, MPIE, MPP=M)
    ebreak
