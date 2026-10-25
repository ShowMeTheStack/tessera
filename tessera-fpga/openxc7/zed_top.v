// ZedBoard top for the open-source flow (yosys + nextpnr-xilinx + prjxray): the Zynq PS7
// primitive, its M_AXI_GP0 port (AXI3) narrowed to the SoC's AXI-Lite slave, FCLK_CLK0 as
// the clock. The PS7 configuration (FCLK frequency, the GP0 port) comes from ps7_init at run time.
module top(output [7:0] leds);
  wire [3:0] fclks, frsts;
  wire aclk;
  BUFG bufg(.I(fclks[0]), .O(aclk));
  // FCLK_RESET0_N is asynchronous to the fabric: synchronise its release
  reg [1:0] rst_sync = 2'b00;
  always @(posedge aclk) rst_sync <= {rst_sync[0], frsts[0]};
  wire aresetn = rst_sync[1];

  wire [31:0] awaddr, araddr, wdata;
  wire [3:0] wstrb;
  wire awvalid, wvalid, arvalid, bready, rready;
  wire [11:0] awid, arid;
  wire awready, wready, bvalid, arready, rvalid;
  wire [1:0] bresp, rresp;
  wire [31:0] rdata;
  // AXI3 transfers from GP0 are single beats here (word accesses): keep the IDs for the responses
  reg [11:0] bid, rid;
  always @(posedge aclk) begin
    if (awvalid && awready) bid <= awid;
    if (arvalid && arready) rid <= arid;
  end

  RvSoc soc(.aclk(aclk), .aresetn(aresetn),
    .s_axi_awaddr(awaddr[17:0]), .s_axi_awvalid(awvalid), .s_axi_awready(awready),
    .s_axi_wdata(wdata), .s_axi_wstrb(wstrb), .s_axi_wvalid(wvalid), .s_axi_wready(wready),
    .s_axi_bresp(bresp), .s_axi_bvalid(bvalid), .s_axi_bready(bready),
    .s_axi_araddr(araddr[17:0]), .s_axi_arvalid(arvalid), .s_axi_arready(arready),
    .s_axi_rdata(rdata), .s_axi_rresp(rresp), .s_axi_rvalid(rvalid), .s_axi_rready(rready),
    .leds(leds));

  (* keep *) PS7 ps7_i(
    .FCLKCLK(fclks), .FCLKRESETN(frsts), .MAXIGP0ACLK(aclk),
    .MAXIGP0AWADDR(awaddr), .MAXIGP0AWVALID(awvalid), .MAXIGP0AWREADY(awready), .MAXIGP0AWID(awid),
    .MAXIGP0WDATA(wdata), .MAXIGP0WSTRB(wstrb), .MAXIGP0WVALID(wvalid), .MAXIGP0WREADY(wready),
    .MAXIGP0BVALID(bvalid), .MAXIGP0BREADY(bready), .MAXIGP0BID(bid), .MAXIGP0BRESP(bresp),
    .MAXIGP0ARADDR(araddr), .MAXIGP0ARVALID(arvalid), .MAXIGP0ARREADY(arready), .MAXIGP0ARID(arid),
    .MAXIGP0RDATA(rdata), .MAXIGP0RVALID(rvalid), .MAXIGP0RREADY(rready), .MAXIGP0RID(rid),
    .MAXIGP0RLAST(1'b1), .MAXIGP0RRESP(rresp));
endmodule
