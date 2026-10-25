import chisel3._
import chisel3.util._

class Design extends Module {
  val y = IO(Output(UInt(8.W)))
  y := 300.U(8.W) // MISTAKE
}
