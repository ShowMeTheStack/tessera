import chisel3._
import chisel3.util._
class Child(fromParent: UInt) extends Module {
  val y = IO(Output(UInt(8.W)))
  y := fromParent // MISTAKE
}

class Design extends Module {
  val a = IO(Input(UInt(8.W)))
  val y = IO(Output(UInt(8.W)))
  val c = Module(new Child(a))
  y := 0.U
}
