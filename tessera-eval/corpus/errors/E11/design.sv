module dut(input logic [1:0] op, input logic [7:0] a, b, output logic [7:0] y);
  always_comb
    case (op) // MISTAKE
      2'd0: y = a + b;
      2'd1: y = -a;
    endcase
endmodule
