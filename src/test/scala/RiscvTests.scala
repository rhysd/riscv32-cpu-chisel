package cpu

import chisel3._
import org.scalatest._
import chiseltest._

class RiscvTests extends FlatSpec with ChiselScalatestTester {
  behavior of "mycpu"
  it should "work though hex" in {
    test(new Top) { c =>
      // riscv-tests test case finishes when program counter is at 0x44
      while (c.io.pc.peek().litValue != 0x44) {
        c.clock.step(1)
      }
      // riscv-tests sets 1 to gp when the test passed otherwise gp represents which test case failed
      c.io.gp.expect(1.U)
    }
  }
}
