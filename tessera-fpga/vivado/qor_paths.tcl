# qor.tcl plus the worst setup paths: the same out-of-context flow, then the top <n> paths (one per
# endpoint) as paths.tsv and as a full text report, paths.rpt.
# Usage: vivado -mode batch -source qor_paths.tcl -tclargs <top> <clock port> <period ns> <n> <files...>
# paths.tsv: one line per path, then one line per pin on it:
#   P <rank> <slack> <datapath delay> <logic levels> <startpoint> <endpoint>
#   N <rank> <pin> <cell> <ref (primitive)> <net leaving the pin, or ->
set top [lindex $argv 0]; set clk [lindex $argv 1]; set period [lindex $argv 2]; set n [lindex $argv 3]
set files [lrange $argv 4 end]
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
report_timing -max_paths $n -nworst 1 -unique_pins -setup -path_type full -file paths.rpt
set out [open paths.tsv w]
set rank 0
foreach p [get_timing_paths -max_paths $n -nworst 1 -unique_pins -setup] {
  incr rank
  puts $out [join [list P $rank [get_property SLACK $p] [get_property DATAPATH_DELAY $p] [get_property LOGIC_LEVELS $p] \
      [get_property STARTPOINT_PIN $p] [get_property ENDPOINT_PIN $p]] "\t"]
  foreach pin [get_pins -of_objects $p] {
    set cell [get_cells -of_objects $pin]
    set net [get_nets -quiet -of_objects $pin]
    if {$net eq ""} { set net - }
    puts $out [join [list N $rank $pin $cell [get_property REF_NAME $cell] $net] "\t"]
  }
}
close $out
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
set u [report_utilization -return_string]
proc cell {u name} { if {[regexp "\\| $name\\s+\\|\\s+(\\d+)" $u -> v]} { return $v } else { return 0 } }
puts "QOR $top LUT [cell $u {Slice LUTs\*?}] FF [cell $u {Slice Registers}] BRAM [cell $u {Block RAM Tile}] DSP [cell $u DSPs] WNS $wns PERIOD $period FMAX [format %.1f [expr {1000.0 / ($period - $wns)}]]"
