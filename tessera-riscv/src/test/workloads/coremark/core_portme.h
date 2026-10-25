/* CoreMark port for Tessera's cores (bare metal, no OS, no timer: the harness counts the
   cycles between the marker instructions retired by start_time and stop_time). */
#ifndef CORE_PORTME_H
#define CORE_PORTME_H
#include <stddef.h>
#include <stdint.h>
#define HAS_FLOAT 0
#define HAS_TIME_H 0
#define USE_CLOCK 0
#define HAS_STDIO 0
#define HAS_PRINTF 0
#define MAIN_HAS_NOARGC 1
#define MAIN_HAS_NORETURN 0
#define SEED_METHOD SEED_VOLATILE
#define MEM_METHOD MEM_STATIC
#define MULTITHREAD 1
#define USE_PTHREAD 0
#define USE_FORK 0
#define USE_SOCKET 0
#define COMPILER_VERSION __VERSION__
#define COMPILER_FLAGS FLAGS_STR
#define MEM_LOCATION "STATIC"
typedef int16_t ee_s16;
typedef uint16_t ee_u16;
typedef int32_t ee_s32;
typedef double ee_f32;
typedef uint8_t ee_u8;
typedef uint32_t ee_u32;
typedef uintptr_t ee_ptr_int;
typedef size_t ee_size_t;
typedef ee_u32 CORE_TICKS;
#define align_mem(x) (void *)(4 + (((ee_ptr_int)(x) - 1) & ~3))
#define NULL_ ((void *)0)
typedef struct CORE_PORTABLE_S { ee_u8 portable_id; } core_portable;
void portable_init(core_portable *p, int *argc, char *argv[]);
void portable_fini(core_portable *p);
extern ee_u32 default_num_contexts;
int ee_printf(const char *fmt, ...);
#endif
