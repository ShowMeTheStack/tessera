import chisel3._
import chisel3.util._

class Design extends Module {
  val fastClock = IO(Input(Clock()))
  val y = IO(Output(UInt(8.W)))
  val a = withClock(fastClock) { RegInit(0.U(8.W)) }
  a := a + 1.U
  val b = RegNext(a) // MISTAKE
  y := b
}
