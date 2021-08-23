package cpu

import chisel3._
import chisel3.util._

class Top extends Module {
  val io = IO(new Bundle {
    val exit = Output(Bool())
  })

  val core = Module(new Core())
  val memory = Module(new Memory())

  // Connect addr and inst ports
  core.io.imem <> memory.io.imem

  // Connect exit signal
  io.exit := core.io.exit
}
