module dut(input logic [7:0] v [4], output logic [7:0] y);
  assign y = v[5]; // MISTAKE
endmodule
