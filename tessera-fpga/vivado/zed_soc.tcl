# ZedBoard SoC: Zynq PS7 (ZedBoard preset) + the Tessera SoC (tessera_soc.sv, top RvSoc) as an
# AXI-Lite slave on M_AXI_GP0 at 0x43C00000, clocked by FCLK_CLK0; LEDs LD0-LD7.
# Usage (Vivado 2020.1, cwd = the build directory holding tessera_soc.sv):
#   vivado -mode batch -source zed_soc.tcl -tclargs <fclk_MHz>
# Writes soc.bit, soc.xsa (contains ps7_init.tcl), utilization.rpt, timing.rpt.
set fclk [lindex $argv 0]; if {$fclk eq ""} { set fclk 50 }
set part xc7z020clg484-1
set_param general.maxThreads 8
create_project soc proj -part $part -force
set_property board_part em.avnet.com:zed:part0:1.4 [current_project]

# module references must be Verilog: a thin wrapper around the SystemVerilog top
set f [open tessera_soc_wrap.v w]
puts $f {module tessera_soc_wrap (
  input  wire aclk, input wire aresetn,
  input  wire [17:0] s_axi_awaddr, input wire s_axi_awvalid, output wire s_axi_awready,
  input  wire [31:0] s_axi_wdata, input wire [3:0] s_axi_wstrb, input wire s_axi_wvalid, output wire s_axi_wready,
  output wire [1:0] s_axi_bresp, output wire s_axi_bvalid, input wire s_axi_bready,
  input  wire [17:0] s_axi_araddr, input wire s_axi_arvalid, output wire s_axi_arready,
  output wire [31:0] s_axi_rdata, output wire [1:0] s_axi_rresp, output wire s_axi_rvalid, input wire s_axi_rready,
  output wire [7:0] leds);
  RvSoc soc (.aclk(aclk), .aresetn(aresetn),
    .s_axi_awaddr(s_axi_awaddr), .s_axi_awvalid(s_axi_awvalid), .s_axi_awready(s_axi_awready),
    .s_axi_wdata(s_axi_wdata), .s_axi_wstrb(s_axi_wstrb), .s_axi_wvalid(s_axi_wvalid), .s_axi_wready(s_axi_wready),
    .s_axi_bresp(s_axi_bresp), .s_axi_bvalid(s_axi_bvalid), .s_axi_bready(s_axi_bready),
    .s_axi_araddr(s_axi_araddr), .s_axi_arvalid(s_axi_arvalid), .s_axi_arready(s_axi_arready),
    .s_axi_rdata(s_axi_rdata), .s_axi_rresp(s_axi_rresp), .s_axi_rvalid(s_axi_rvalid), .s_axi_rready(s_axi_rready),
    .leds(leds));
endmodule}
close $f
add_files [list tessera_soc.sv tessera_soc_wrap.v]
set_property file_type SystemVerilog [get_files tessera_soc.sv]
update_compile_order -fileset sources_1

create_bd_design soc
set ps [create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7:5.5 ps7]
apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 -config {make_external "FIXED_IO, DDR" apply_board_preset "1" Master "Disable" Slave "Disable"} $ps
set_property -dict [list CONFIG.PCW_USE_M_AXI_GP0 {1} CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ $fclk] $ps
set soc [create_bd_cell -type module -reference tessera_soc_wrap tessera]
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config {Master "/ps7/M_AXI_GP0" Clk "Auto"} [get_bd_intf_pins tessera/s_axi]
make_bd_pins_external [get_bd_pins tessera/leds]
set_property name leds [get_bd_ports leds_0]
# the slave decodes 18 address bits: 256 KiB at 0x43C00000
set seg [get_bd_addr_segs -of_objects [get_bd_addr_spaces ps7/Data] -filter {NAME =~ "*tessera*"}]
set_property range 256K $seg
set_property offset 0x43C00000 $seg
validate_bd_design
save_bd_design
make_wrapper -files [get_files soc.bd] -top
add_files -norecurse [glob proj/soc.srcs/sources_1/bd/soc/hdl/soc_wrapper.v]
set_property top soc_wrapper [current_fileset]

set xdc [open leds.xdc w]
foreach {i pin} {0 T22 1 T21 2 U22 3 U21 4 V22 5 W22 6 U19 7 U14} {
  puts $xdc "set_property -dict {PACKAGE_PIN $pin IOSTANDARD LVCMOS33} \[get_ports {leds\[$i\]}\]"
}
close $xdc
add_files -fileset constrs_1 leds.xdc

launch_runs synth_1 -jobs 8
wait_on_run synth_1
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
open_run impl_1
report_utilization -file utilization.rpt
report_utilization -hierarchical -hierarchical_depth 3 -file utilization_hier.rpt
report_timing_summary -file timing.rpt
file copy -force [glob proj/soc.runs/impl_1/soc_wrapper.bit] soc.bit
write_hw_platform -fixed -include_bit -force soc.xsa
puts "WNS [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]"
puts "TESSERA_BUILD_DONE"
