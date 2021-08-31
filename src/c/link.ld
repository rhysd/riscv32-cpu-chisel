ENTRY(_start)

SECTIONS
{
  . = 0x00000000;
  .text : {
    *(.init)
    *(.text)
  }
}
