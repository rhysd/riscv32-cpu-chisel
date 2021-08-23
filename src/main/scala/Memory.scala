package cpu

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import common.Consts._

// Group input/output between CPU core and memory (see p.77)
class ImemPortIo extends Bundle {
  // input port for memory address
  val addr = Input(UInt(WORD_LEN.W))
  // output port for fetching instruction
  val inst = Output(UInt(WORD_LEN.W))
}

class Memory extends Module {
  val io = IO(new Bundle {
    val imem = new ImemPortIo()
  })

  // As underlying implementation, memory consists of 8bit * 0x4000 registers (16KB). The register
  // size is 8bits because PC is counted up by 4bytes.
  val mem = Mem(0x4000, UInt(8.W))

  loadMemoryFromFile(mem, "src/hex/fetch.hex")

  io.imem.inst := Cat(
    mem(io.imem.addr + 3.U(WORD_LEN.W)),
    mem(io.imem.addr + 2.U(WORD_LEN.W)),
    mem(io.imem.addr + 1.U(WORD_LEN.W)),
    mem(io.imem.addr)
  )
}
