# External interrupt: count in s1 until the handler runs; the handler masks MEIE and sets s2.
    .section .text.init
    .globl _start
_start:
    li    s1, 0                 # before interrupts are enabled: the irq may already be pending
    li    s2, 0
    la    t0, handler
    csrw  mtvec, t0
    li    t0, 0x800
    csrw  mie, t0               # MEIE
    csrsi mstatus, 8            # MIE
loop:
    addi  s1, s1, 1
    beqz  s2, loop
    csrr  s3, mcause            # 0x8000000B
    csrr  s4, mstatus           # MIE back on after mret: 0x1888
    ebreak

handler:
    li    s2, 1
    csrw  mie, x0               # stop further external interrupts
    csrr  s5, mepc              # the interrupted instruction in the loop
    mret
