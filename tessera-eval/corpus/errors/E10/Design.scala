import chisel3._
import chisel3.util._
object Light extends ChiselEnum { val Red, Green = Value }
object Door extends ChiselEnum { val Open, Shut = Value }

class Design extends Module {
  val a = IO(Input(Light())); val b = IO(Input(Door()))
  val y = IO(Output(Bool()))
  y := a === b // MISTAKE
}
