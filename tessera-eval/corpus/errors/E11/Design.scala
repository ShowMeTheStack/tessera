import chisel3._
import chisel3.util._

class Design extends Module {
  val op = IO(Input(UInt(2.W))); val a = IO(Input(UInt(8.W))); val b = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  switch(op) { // MISTAKE
    is(0.U) { y := a + b }
    is(1.U) { y := 0.U - a }
  }
}
