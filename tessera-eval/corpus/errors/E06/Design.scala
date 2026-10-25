import chisel3._
import chisel3.util._

class Design extends Module {
  val addr = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  val m = Mem(16, UInt(8.W))
  y := m(addr) // MISTAKE
}
