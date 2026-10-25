module dut(input logic [7:0] b, output logic [7:0] c);
  assign c = b + 8'd1; // MISTAKE
endmodule
