import chisel3._
import chisel3.util._

class Design extends Module {
  val c = IO(Input(Bool())); val a = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  val w = Wire(UInt(8.W))
  when(c) { w := a } // MISTAKE
  y := w
}
