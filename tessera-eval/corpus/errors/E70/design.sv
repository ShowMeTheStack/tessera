module dut(input logic clk, input logic reset, output logic [3:0] y);
  logic [3:0] r;
  always_ff @(posedge clk) r <= reset ? 4'd0 : (r == 4'd10 ? 4'd0 : r + 4'd1); // MISTAKE
  assign y = r;
endmodule
