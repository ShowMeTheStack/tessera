module dut(input logic clk, input logic fast_clk, output logic y);
  logic a, s1;
  always_ff @(posedge fast_clk) a <= ~a;
  always_ff @(posedge clk) s1 <= a; // MISTAKE
  assign y = s1;
endmodule
