module dut(input logic [31:0] u, output int x);
  assign x = u; // MISTAKE
endmodule
