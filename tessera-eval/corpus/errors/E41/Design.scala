import chisel3._
import chisel3.util._

class Design extends Module {
  val fastClock = IO(Input(Clock()))
  val y = IO(Output(UInt(2.W)))
  val a = withClock(fastClock) { RegInit(0.U(4.W)) }
  a := a + 1.U
  y := RegNext(RegNext(a(1, 0))) // MISTAKE
}
