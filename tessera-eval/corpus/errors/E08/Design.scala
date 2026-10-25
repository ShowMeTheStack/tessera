import chisel3._
import chisel3.util._

class Design extends Module {
  val s = IO(Input(SInt(8.W))); val u = IO(Input(UInt(8.W)))
  val y = IO(Output(Bool()))
  y := s < u // MISTAKE
}
