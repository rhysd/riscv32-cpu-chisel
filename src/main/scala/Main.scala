package cpu

import chisel3._
import chisel3.stage.ChiselStage

object MyCpuDriver extends App {
  (new ChiselStage).emitVerilog(new Top, args)
}
