OUTPUT_ARCH("riscv")
ENTRY(_start)
SECTIONS
{
  . = 0x00000000;
  .text.init : { *(.text.init) }
  .text : { *(.text) }
  . = ALIGN(0x1000);
  .data : { *(.data) *(.sdata) *(.rodata*) }
  .bss : { *(.bss) *(.sbss) }
  _end = .;
}
