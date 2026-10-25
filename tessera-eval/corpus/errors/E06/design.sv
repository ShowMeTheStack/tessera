module dut(input logic clk, input logic [7:0] addr, output logic [7:0] y);
  logic [7:0] m [0:15];
  assign y = m[addr]; // MISTAKE
endmodule
