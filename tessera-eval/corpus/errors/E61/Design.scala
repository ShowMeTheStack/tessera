import chisel3._
import chisel3.util._

class Design extends Module {
  val u = IO(Input(UInt(32.W))); val i = IO(Input(SInt(32.W)))
  val b = IO(Output(Bool()))
  b := u < i // MISTAKE
}
