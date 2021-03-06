package cpu

import chisel3._
import chisel3.util._
import Consts._

class Top(hexMemoryPath: String) extends Module {
  val io = IO(new Bundle {
    val exit = Output(Bool())
    val gp = Output(UInt(WORD_LEN.W))
    val pc = Output(UInt(WORD_LEN.W))
  })

  val core = Module(new Core())
  val memory = Module(new Memory(hexMemoryPath))

  // Connect ports between core and memory
  core.io.imem <> memory.io.imem
  core.io.dmem <> memory.io.dmem

  // Connect signals inside core
  io.exit := core.io.exit
  io.gp := core.io.gp
  io.pc := core.io.pc
}
