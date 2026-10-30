# Side effects of trapping stores: a misaligned sw or sh traps and must not write memory, and a store right behind
# a trapping instruction must not commit before the trap (it runs once, after the handler returns).
# The handler skips the faulting instruction (mepc + 4). Co-simulation compares every memory write with the ISS.
    .section .text.init
    .globl _start
_start:
    la    t0, handler
    csrw  mtvec, t0
    li    s0, 0                 # traps taken
    li    t1, 0x2000            # the buffer
    li    t2, 0x11223344
    sw    t2, 0(t1)             # committed
    li    t3, 0x55667788
    sw    t3, 2(t1)             # misaligned: traps, no write
    sh    t3, 5(t1)             # misaligned: traps, no write
    sb    t3, 9(t1)             # committed
    lw    t4, 1(t1)             # misaligned load: traps ...
    sw    t3, 12(t1)            # ... and this store must not commit before it (it runs after the handler)
    ecall                       # traps ...
    sw    t2, 16(t1)            # ... the same behind an ecall
    li    t5, 3
    li    t6, 0
    div   t6, t3, t5            # a divide in flight ...
    sw    t3, 3(t1)             # ... then a misaligned store behind it: traps, no write
    sw    t6, 20(t1)            # committed (the quotient)
    lw    s1, 0(t1)
    lw    s2, 8(t1)
    lw    s3, 12(t1)
    lw    s4, 16(t1)
    lw    s5, 20(t1)
    mv    s6, s0                # five traps
    ebreak

handler:
    addi  s0, s0, 1
    csrr  a6, mepc
    addi  a6, a6, 4
    csrw  mepc, a6
    mret
