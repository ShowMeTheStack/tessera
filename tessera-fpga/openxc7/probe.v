// openXC7 probe: PS7 FCLK0 clock and an M_AXI_GP0 register, to check PS7 routing in nextpnr-xilinx
module top(output [7:0] leds);
  wire [3:0] fclks, frsts;
  wire clk;
  wire frstn = frsts[0];
  wire [31:0] awaddr, araddr, wdata;
  wire awvalid, wvalid, arvalid, bready, rready;
  wire [11:0] awid, arid;
  reg awready_r, bvalid_r, arready_r, rvalid_r;
  reg [11:0] bid_r, rid_r;
  reg [31:0] value, rdata_r;
  BUFG bufg(.I(fclks[0]), .O(clk));
  always @(posedge clk) begin
    if (!frstn) begin
      awready_r <= 0; bvalid_r <= 0; arready_r <= 0; rvalid_r <= 0; value <= 32'h12345678;
    end else begin
      awready_r <= awvalid && wvalid && !awready_r && !bvalid_r;
      if (awready_r) begin bvalid_r <= 1; bid_r <= awid; value <= wdata; end
      else if (bready) bvalid_r <= 0;
      arready_r <= arvalid && !arready_r && !rvalid_r;
      if (arready_r) begin rvalid_r <= 1; rid_r <= arid; rdata_r <= value ^ araddr; end
      else if (rready) rvalid_r <= 0;
    end
  end
  reg [26:0] ctr;
  always @(posedge clk) ctr <= ctr + 1;
  assign leds = {ctr[26:23], value[3:0]};
  (* keep *) PS7 ps7_i(
    .FCLKCLK(fclks), .FCLKRESETN(frsts),
    .MAXIGP0ACLK(clk),
    .MAXIGP0AWADDR(awaddr), .MAXIGP0AWVALID(awvalid), .MAXIGP0AWREADY(awready_r), .MAXIGP0AWID(awid),
    .MAXIGP0WDATA(wdata), .MAXIGP0WVALID(wvalid), .MAXIGP0WREADY(awready_r),
    .MAXIGP0BVALID(bvalid_r), .MAXIGP0BREADY(bready), .MAXIGP0BID(bid_r), .MAXIGP0BRESP(2'b00),
    .MAXIGP0ARADDR(araddr), .MAXIGP0ARVALID(arvalid), .MAXIGP0ARREADY(arready_r), .MAXIGP0ARID(arid),
    .MAXIGP0RDATA(rdata_r), .MAXIGP0RVALID(rvalid_r), .MAXIGP0RREADY(rready), .MAXIGP0RID(rid_r),
    .MAXIGP0RLAST(1'b1), .MAXIGP0RRESP(2'b00));
endmodule
