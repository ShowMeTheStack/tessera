module dut(input logic signed [7:0] s, input logic [7:0] u, output logic y);
  assign y = s < u; // MISTAKE
endmodule
