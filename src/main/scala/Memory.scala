package cpu

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import common.Consts._

// Group input/output between CPU core and memory (see p.77)
// 'I' stands for 'Instruction'.
class ImemPortIo extends Bundle {
  // input port for memory address
  val addr = Input(UInt(WORD_LEN.W))
  // output port for fetching instruction
  val inst = Output(UInt(WORD_LEN.W))
}

class DmemPortIo extends Bundle {
  val addr = Input(UInt(WORD_LEN.W))
  val rdata = Output(UInt(WORD_LEN.W))
}

// Group input/output between CPU core and memory (p.92)
// 'D' stands for 'data'.
class Memory extends Module {
  val io = IO(new Bundle {
    val imem = new ImemPortIo()
    val dmem = new DmemPortIo()
  })

  // As underlying implementation, memory consists of 8bit * 0x4000 registers (16KB). The register
  // size is 8bits because PC is counted up by 4bytes.
  val mem = Mem(0x4000, UInt(8.W))

  loadMemoryFromFile(mem, "src/hex/lw.hex")

  io.imem.inst := Cat(
    mem(io.imem.addr + 3.U(WORD_LEN.W)),
    mem(io.imem.addr + 2.U(WORD_LEN.W)),
    mem(io.imem.addr + 1.U(WORD_LEN.W)),
    mem(io.imem.addr)
  )

  io.dmem.rdata := Cat(
    mem(io.dmem.addr + 3.U(WORD_LEN.W)),
    mem(io.dmem.addr + 2.U(WORD_LEN.W)),
    mem(io.dmem.addr + 1.U(WORD_LEN.W)),
    mem(io.dmem.addr)
  )
}
