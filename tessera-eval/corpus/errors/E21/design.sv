module dut(input logic c, input logic [7:0] a, output logic [7:0] y);
  always_comb if (c) y = a; // MISTAKE
endmodule
