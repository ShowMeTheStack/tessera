# Start-up for the workloads on Tessera's cores: one 64 KiB address space (the image is loaded
# into both the instruction and the data memory), stack at the top, bss cleared here.
# main's return value is left in a0, then EBREAK halts the core.
    .section .text.start
    .globl _start
_start:
    .option push
    .option norelax
    la gp, __global_pointer$
    .option pop
    la sp, __stack_top
    la t0, __bss_start
    la t1, __bss_end
1:  bgeu t0, t1, 2f
    sw zero, 0(t0)
    addi t0, t0, 4
    j 1b
2:  call main
    ebreak

# exit(code) from the C library ends the same way
    .globl _exit
_exit:
    ebreak
