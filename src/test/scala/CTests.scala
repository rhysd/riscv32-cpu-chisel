package cpu

import java.io.File
import chisel3._
import org.scalatest._
import chiseltest._
import Consts._

class CTests extends FlatSpec with ChiselScalatestTester {
  behavior of "mycpu"

  for (f <- new File("./c").listFiles.filter(f => f.isFile && f.getName.endsWith(".hex"))) {
    val p = f.getPath
    it should p in {
      test(new Top(p)) { c =>
        // c is an instance of Top
        while (!c.io.exit.peek().litToBoolean) {
          c.clock.step(1)
        }
      }
    }
  }
}
