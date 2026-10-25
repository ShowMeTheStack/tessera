import chisel3._
import chisel3.util._

class Design extends Module {
  val a = IO(Input(UInt(8.W))); val c = IO(Input(UInt(4.W)))
  val y = IO(Output(UInt(8.W)))
  y := a & c // MISTAKE
}
