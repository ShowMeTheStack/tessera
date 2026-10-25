# The Zynq PS7 configuration alone (ZedBoard preset, M_AXI_GP0, FCLK0 at <fclk_MHz>), for an openXC7
# build at a clock no Vivado SoC build used: writes ps7_init.tcl (and soc.xsa) in the current directory.
# Usage: vivado -mode batch -source ps7_only.tcl -tclargs <fclk_MHz>
set fclk [lindex $argv 0]
create_project ps7 proj -part xc7z020clg484-1 -force
set_property board_part em.avnet.com:zed:part0:1.4 [current_project]
create_bd_design soc
set ps [create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7:5.5 ps7]
apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 -config {make_external "FIXED_IO, DDR" apply_board_preset "1" Master "Disable" Slave "Disable"} $ps
set_property -dict [list CONFIG.PCW_USE_M_AXI_GP0 {1} CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ $fclk] $ps
connect_bd_net [get_bd_pins ps7/FCLK_CLK0] [get_bd_pins ps7/M_AXI_GP0_ACLK]
validate_bd_design
save_bd_design
generate_target all [get_files soc.bd]
write_hw_platform -fixed -force soc.xsa
puts "FCLK0_ACTUAL [get_property CONFIG.PCW_ACT_FPGA0_PERIPHERAL_FREQMHZ $ps]"
