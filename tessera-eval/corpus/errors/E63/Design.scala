import chisel3._
import chisel3.util._

class Design extends Module {
  val u = IO(Input(UInt(32.W)))
  val x = IO(Output(SInt(32.W)))
  x := u // MISTAKE
}
