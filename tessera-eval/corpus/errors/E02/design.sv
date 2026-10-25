module dut(input logic [7:0] a, input logic [3:0] c, output logic [7:0] y);
  assign y = a & c; // MISTAKE
endmodule
