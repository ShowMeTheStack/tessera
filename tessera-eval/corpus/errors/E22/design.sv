module dut(output logic [7:0] y);
  logic [7:0] w;
  assign w = w + 8'd1; // MISTAKE
  assign y = w;
endmodule
