# A CSR instruction held behind a divide while an external interrupt arrives (the harness raises irq at many
# different cycles). If the core wrote the CSR while the instruction was held and then took the interrupt before
# it retired, the instruction runs again after the handler and reads the value it wrote itself: co-simulation
# sees the different rd value, and the CSR history differs from the ISS's.
    .section .text.init
    .globl _start
_start:
    li    s1, 0                 # loop counter
    li    s2, 0                 # set by the handler
    li    s6, 0                 # sum of the values read back
    li    s7, 24                # iterations
    li    t4, 1000003
    li    t5, 7
    la    t0, handler
    csrw  mtvec, t0
    li    t0, 0x800
    csrw  mie, t0               # MEIE
    csrsi mstatus, 8            # MIE (the irq may already be pending: everything above is set up before)
loop:
    div   t3, t4, t5            # 16 cycles of the sequential divider: what follows is held
    csrrw t0, mscratch, s1      # reads the previous iteration's value, writes this one's
    add   s6, s6, t0
    csrrs t1, mtvec, x0         # a read only
    addi  s1, s1, 1
    blt   s1, s7, loop
    csrr  s3, mscratch
    ebreak

handler:
    li    s2, 1
    csrw  mie, x0               # one interrupt only
    mret
