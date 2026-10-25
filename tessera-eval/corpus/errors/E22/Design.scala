import chisel3._
import chisel3.util._

class Design extends Module {
  val y = IO(Output(UInt(8.W)))
  val w = Wire(UInt(8.W))
  w := w + 1.U // MISTAKE
  y := w
}
