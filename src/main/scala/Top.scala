package cpu

import chisel3._
import chisel3.util._

class Top extends Module {
  val io = IO(new Bundle {
    val exit = Output(Bool())
  })

  val core = Module(new Core())
  val memory = Module(new Memory())

  // Connect ports between core and memory
  core.io.imem <> memory.io.imem
  core.io.dmem <> memory.io.dmem

  // Connect exit signal
  io.exit := core.io.exit
}
