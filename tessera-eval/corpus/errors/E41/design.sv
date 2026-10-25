module dut(input logic clk, input logic fast_clk, output logic [1:0] y);
  logic [3:0] a;
  logic [1:0] s1, s2;
  always_ff @(posedge fast_clk) a <= a + 4'd1;
  always_ff @(posedge clk) begin s1 <= a[1:0]; s2 <= s1; end // MISTAKE
  assign y = s2;
endmodule
