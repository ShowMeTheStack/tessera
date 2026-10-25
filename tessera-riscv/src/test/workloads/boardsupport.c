/* Embench board support for Tessera's cores: the triggers retire marker instructions
   (addi x0, x0, 1 and 2), which the simulator and the board harness use to time the
   benchmark region. */
#include <support.h>

void initialise_board(void) {}

void __attribute__((noinline)) __attribute__((externally_visible)) start_trigger(void) {
    __asm__ volatile("addi x0, x0, 1" ::: "memory");
}

void __attribute__((noinline)) __attribute__((externally_visible)) stop_trigger(void) {
    __asm__ volatile("addi x0, x0, 2" ::: "memory");
}
