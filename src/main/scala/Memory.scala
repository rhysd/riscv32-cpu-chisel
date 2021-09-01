package cpu

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import Consts._

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
  val rdata = Output(UInt(WORD_LEN.W)) // r stands for read
  val wen = Input(Bool()) // Indicate when data should be written
  val wdata = Input(UInt(WORD_LEN.W)) // w stands for write
}

// Group input/output between CPU core and memory (p.92)
// 'D' stands for 'data'.
class Memory(hexMemoryPath: String) extends Module {
  val io = IO(new Bundle {
    val imem = new ImemPortIo()
    val dmem = new DmemPortIo()
  })

  // As underlying implementation, memory consists of 8bit * 0x4000 registers (16KB). The register
  // size is 8bits because PC is counted up by 4bytes.
  val mem = Mem(0x4000, UInt(8.W))

  loadMemoryFromFile(mem, hexMemoryPath)

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

  when(io.dmem.wen) {
    mem(io.dmem.addr + 3.U(WORD_LEN.W)) := io.dmem.wdata(31, 24)
    mem(io.dmem.addr + 2.U(WORD_LEN.W)) := io.dmem.wdata(23, 16)
    mem(io.dmem.addr + 1.U(WORD_LEN.W)) := io.dmem.wdata(15, 8)
    mem(io.dmem.addr) := io.dmem.wdata(7, 0)
  }
}
