import chisel3._
import chisel3.util._

class Design extends Module {
  val x = IO(Output(UInt(8.W)))
  x := 300.U // MISTAKE
}
