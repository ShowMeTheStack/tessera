# Go/no-go check: does Vivado infer a true dual-port block RAM from Tessera's Mem.port template?
# Out-of-context synthesis of one design on the ZedBoard's part; prints one GATE line per block RAM with its
# mode and port settings, and the utilisation.
# Usage: vivado -mode batch -source tdp_gate.tcl -tclargs <top> <file.sv>
set top [lindex $argv 0]; set f [lindex $argv 1]
set_param general.maxThreads 4
read_verilog -sv $f
synth_design -top $top -part xc7z020clg484-1 -mode out_of_context
report_utilization -file util.rpt
set u [report_utilization -return_string]
proc cell {u name} { if {[regexp "\\| $name\\s+\\|\\s+(\\S+)" $u -> v]} { return $v } else { return 0 } }
puts "GATE $top LUT [cell $u {Slice LUTs\*?}] FF [cell $u {Slice Registers}] BRAM_TILE [cell $u {Block RAM Tile}] RAMB36 [cell $u RAMB36/FIFO\*?] RAMB18 [cell $u RAMB18]"
foreach c [get_cells -hier -filter {PRIMITIVE_TYPE =~ BMEM.*.*}] {
  set p {}
  foreach k {REF_NAME RAM_MODE READ_WIDTH_A WRITE_WIDTH_A READ_WIDTH_B WRITE_WIDTH_B WRITE_MODE_A WRITE_MODE_B RDADDR_COLLISION_HWCONFIG DOA_REG DOB_REG} {
    lappend p "$k=[get_property $k $c]"
  }
  puts "GATE_BRAM $c [join $p { }]"
}
foreach n {WEA WEBWE ENARDEN ENBWREN} {
  foreach c [get_cells -hier -filter {PRIMITIVE_TYPE =~ BMEM.*.*}] {
    set nets {}
    foreach pin [get_pins -of $c -filter "REF_PIN_NAME =~ $n*"] { lappend nets [get_nets -of $pin] }
    puts "GATE_PIN $c $n [lsort -unique $nets]"
  }
}
