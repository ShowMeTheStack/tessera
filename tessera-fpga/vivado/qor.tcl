# Out-of-context quality of results for one design: synthesis, placement and routing on the
# ZedBoard's xc7z020clg484-1 with the clock on port <clock> at <period> ns; reports utilisation
# and the worst setup slack (Fmax = 1000 / (period - WNS)).
# Usage: vivado -mode batch -source qor.tcl -tclargs <top> <clock port> <period ns> <files...>
set top [lindex $argv 0]; set clk [lindex $argv 1]; set period [lindex $argv 2]
set files [lrange $argv 3 end]
set_param general.maxThreads 4
foreach f $files {
  if {[string match *.sv $f]} { read_verilog -sv $f } else { read_verilog $f }
}
synth_design -top $top -part xc7z020clg484-1 -mode out_of_context -flatten_hierarchy rebuilt
create_clock -name clk -period $period [get_ports $clk]
opt_design
place_design
phys_opt_design
route_design
report_utilization -file util.rpt
report_timing_summary -file timing.rpt
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
set u [report_utilization -return_string]
proc cell {u name} { if {[regexp "\\| $name\\s+\\|\\s+(\\d+)" $u -> v]} { return $v } else { return 0 } }
puts "QOR $top LUT [cell $u {Slice LUTs\*?}] FF [cell $u {Slice Registers}] BRAM [cell $u {Block RAM Tile}] DSP [cell $u DSPs] WNS $wns PERIOD $period FMAX [format %.1f [expr {1000.0 / ($period - $wns)}]]"
