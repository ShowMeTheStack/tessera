module dut(input logic c, input logic signed [7:0] s, input logic [7:0] u, output logic [7:0] y);
  assign y = c ? s : u; // MISTAKE
endmodule
