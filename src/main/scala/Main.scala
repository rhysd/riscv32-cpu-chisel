package cpu

import java.io.File
import chisel3._
import chisel3.stage.ChiselStage

object MyCpuDriver extends App {
  def getMemoryHexFilePath(args: Array[String]): (String, Array[String]) = {
    for (i <- 1 until args.length) {
      if (args(i-1) == "--memoryHexFile") {
        val a = args(i)
        if (!a.isEmpty) {
          if (!new File(a).isFile) {
            throw new IllegalArgumentException(a ++ " is not a file at --memoryHexFile argument")
          }
          return (a, args.take(i-1) ++ args.drop(i + 1))
        }
      }
    }
    throw new IllegalArgumentException("--memoryHexFile {path} must specify non-empty file path")
  }
  val (p, a) = getMemoryHexFilePath(args)
  (new ChiselStage).emitVerilog(new Top(p), a)
}
