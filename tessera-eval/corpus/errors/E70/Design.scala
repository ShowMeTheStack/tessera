import chisel3._
import chisel3.util._

class Design extends Module {
  val y = IO(Output(UInt(4.W)))
  val r = RegInit(0.U(4.W))
  r := Mux(r === 10.U, 0.U, r + 1.U) // MISTAKE
  y := r
}
