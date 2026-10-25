import chisel3._
import chisel3.util._

class Design extends Module {
  val v = IO(Input(Vec(4, UInt(8.W))))
  val y = IO(Output(UInt(8.W)))
  y := v(5) // MISTAKE
}
