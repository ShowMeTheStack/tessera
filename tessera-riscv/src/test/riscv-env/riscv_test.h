// Minimal riscv-tests environment for Tessera cores: no CSRs, no traps.
// The test ends with EBREAK (which halts every Tessera core); gp (x3) == 1 means pass,
// otherwise gp = (failing test << 1) | 1.
#ifndef TESSERA_RISCV_TEST_H
#define TESSERA_RISCV_TEST_H

#define RVTEST_RV32U .macro init; .endm
#define RVTEST_RV64U RVTEST_RV32U
#define TESTNUM gp

#define RVTEST_CODE_BEGIN  \
        .section .text.init; \
        .align 2;            \
        .globl _start;       \
_start:                      \
        li TESTNUM, 0;

#define RVTEST_CODE_END unimp

#define RVTEST_PASS          \
        fence;               \
        li TESTNUM, 1;       \
        ebreak

#define RVTEST_FAIL          \
        fence;               \
1:      beqz TESTNUM, 1b;    \
        sll TESTNUM, TESTNUM, 1; \
        or TESTNUM, TESTNUM, 1;  \
        ebreak

#define EXTRA_DATA
#define RVTEST_DATA_BEGIN EXTRA_DATA .align 4; .global begin_signature; begin_signature:
#define RVTEST_DATA_END .align 4; .global end_signature; end_signature:

#endif
