package cpu

import java.io.File
import chisel3._
import org.scalatest._
import chiseltest._

class RiscvTests extends FlatSpec with ChiselScalatestTester {
  behavior of "mycpu"

  for (f <- new File("./riscv-tests-results").listFiles.filter(f => f.isFile && f.getName.endsWith(".hex"))) {
    val p = f.getPath
    it should p in {
      test(new Top(p)) { c =>
        // riscv-tests test case finishes when program counter is at 0x44
        while (c.io.pc.peek().litValue != 0x44) {
          c.clock.step(1)
        }
        // riscv-tests sets 1 to gp when the test passed otherwise gp represents which test case failed
        c.io.gp.expect(1.U)
      }
    }
  }
}
