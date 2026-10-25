module dut(input logic [31:0] u, input int i, output logic b);
  assign b = u < i; // MISTAKE
endmodule
