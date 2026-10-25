import chisel3._
import chisel3.util._

class Design extends Module {
  val s = IO(Input(SInt(32.W)))
  val u = IO(Output(UInt(32.W)))
  u := s // MISTAKE
}
