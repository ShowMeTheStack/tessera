import chisel3._
import chisel3.util._

class Design extends Module {
  val c = IO(Input(Bool())); val s = IO(Input(SInt(8.W))); val u = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  y := Mux(c, s, u) // MISTAKE
}
