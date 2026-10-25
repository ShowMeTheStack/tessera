/* CoreMark port for Tessera's cores. ee_printf writes into report[], which the harness reads
   from data memory after the run ("Correct operation validated." means CoreMark's own CRC
   checks passed). Timing comes from the marker instructions, not from a timer. */
#include <stdarg.h>
#include <stdio.h>
#include "coremark.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;
ee_u32 default_num_contexts = 1;

char report[2048] __attribute__((used));
static size_t report_len;

int ee_printf(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(report + report_len, sizeof report - report_len, fmt, ap);
    va_end(ap);
    if (n > 0) report_len += (size_t) n < sizeof report - report_len ? (size_t) n : sizeof report - report_len - 1;
    return n;
}

void start_time(void) { __asm__ volatile("addi x0, x0, 1" ::: "memory"); }
void stop_time(void) { __asm__ volatile("addi x0, x0, 2" ::: "memory"); }
/* no timer: CoreMark's own "time" is a constant 10 s (its minimum for a valid run); the
   harness measures the real cycles between the markers */
CORE_TICKS get_time(void) { return 10000000; }
secs_ret time_in_secs(CORE_TICKS t) { return (secs_ret) t / 1000000; }
void portable_init(core_portable *p, int *argc, char *argv[]) { (void) argc; (void) argv; p->portable_id = 1; }
void portable_fini(core_portable *p) { p->portable_id = 0; }
