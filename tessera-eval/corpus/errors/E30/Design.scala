import chisel3._
import chisel3.util._

class Design extends Module {
  val x = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(17.W)))
  val m = RegNext(RegNext(x * x))
  val x1 = RegNext(x)
  y := m +& x1 // MISTAKE
}
