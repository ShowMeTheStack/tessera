OUTPUT_ARCH("riscv")
ENTRY(rvtest_init)
SECTIONS
{
  . = 0x0;
  .text.init : { *(.text.init) }
  .text : { *(.text) }
  . = ALIGN(16);
  .data : { *(.data) *(.data.*) }
  .bss : { *(.bss) }
  _end = .;
}
