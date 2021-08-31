package cpu

import chisel3._
import org.scalatest._
import chiseltest._
import Consts._

class CTests extends FlatSpec with ChiselScalatestTester {
  behavior of "mycpu"
  it should "work though hex" in {
    test(new Top) { c =>
      // c is an instance of Top
      while (!c.io.exit.peek().litToBoolean) {
        c.clock.step(1)
      }
    }
  }
}
