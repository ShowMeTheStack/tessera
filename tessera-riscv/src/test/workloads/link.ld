/* Workloads on Tessera's cores: everything in one 64 KiB space starting at 0. */
OUTPUT_ARCH(riscv)
ENTRY(_start)
MEMORY { RAM (rwx) : ORIGIN = 0, LENGTH = 64K }
SECTIONS {
  .text : { KEEP(*(.text.start)) *(.text .text.*) } > RAM
  .rodata : ALIGN(4) { *(.rodata .rodata.* .srodata .srodata.*) } > RAM
  .data : ALIGN(4) { *(.data .data.* .sdata .sdata.*) } > RAM
  __global_pointer$ = . + 0x800;
  .bss : ALIGN(4) { __bss_start = .; *(.sbss .sbss.* .bss .bss.* COMMON) . = ALIGN(4); __bss_end = .; } > RAM
  __heap_start = .;
  __stack_top = ORIGIN(RAM) + LENGTH(RAM);
  __heap_end = __stack_top - 8K;
  /DISCARD/ : { *(.comment) *(.riscv.attributes) *(.note*) }
}
