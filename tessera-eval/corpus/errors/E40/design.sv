module dut(input logic clk, input logic fast_clk, output logic [7:0] y);
  logic [7:0] a, b;
  always_ff @(posedge fast_clk) a <= a + 8'd1;
  always_ff @(posedge clk) b <= a; // MISTAKE
  assign y = b;
endmodule
