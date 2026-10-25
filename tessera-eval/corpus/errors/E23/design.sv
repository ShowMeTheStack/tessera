module dut(input logic [7:0] a, b, output logic [7:0] y);
  assign y = a;
  assign y = b; // MISTAKE
endmodule
