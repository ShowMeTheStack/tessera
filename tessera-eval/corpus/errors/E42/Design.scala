import chisel3._
import chisel3.util._

class Design extends Module {
  val fastClock = IO(Input(Clock()))
  val y = IO(Output(Bool()))
  val a = withClock(fastClock) { RegInit(false.B) }
  a := !a
  y := RegNext(a) // MISTAKE
}
