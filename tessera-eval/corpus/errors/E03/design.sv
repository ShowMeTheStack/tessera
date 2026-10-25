module dut(input logic [7:0] a, output logic [7:0] y);
  assign y = a[9:2]; // MISTAKE
endmodule
