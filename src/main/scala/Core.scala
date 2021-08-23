package cpu

import chisel3._
import chisel3.util._
import common.Consts._

class Core extends Module {
  val io = IO(new Bundle {
    // Flip input and output to connect to memory
    val imem = Flipped(new ImemPortIo())

    // This port signal will be `true` when a program finished
    val exit = Output(Bool())
  })

  // RISC-V has 32 registers. Size is 32bits (32bits * 32regs).
  val regfile = Mem(32, UInt(WORD_LEN.W))

  /*
   * Instruction Fetch (IF)
   */

  // Program counter register. It counts up per 4 bytes since size of instruction is 32bits, but
  // memory address is byte oriented.
  val pc = RegInit(START_ADDR)
  pc := pc + 4.U(WORD_LEN.W) // Add 4 bytes per cycle

  // Connect program counter to address output. This output is connected to memory to fetch the
  // address as instruction
  io.imem.addr := pc
  val inst = io.imem.inst

  // `exit` port output is `true` when the instruction is 0x34333231. 0x34333231 means the end of a loaded program.
  io.exit := inst === 0x34333231.U(WORD_LEN.W)

  printf(p"pc:   0x${Hexadecimal(pc)}\n")
  printf(p"inst: 0x${Hexadecimal(inst)}\n")
  printf("-------------\n")
}
