module mul2(input logic clk, input logic [7:0] a, b, output logic [15:0] out);
  logic [15:0] p1, p2;
  always_ff @(posedge clk) begin p1 <= a * b; p2 <= p1; end
  assign out = p2;
endmodule
module dut(input logic clk, input logic [7:0] x, output logic [16:0] y);
  logic [7:0] x1;
  logic [15:0] m;
  mul2 u(.clk(clk), .a(x), .b(x), .out(m));
  always_ff @(posedge clk) x1 <= x;
  assign y = {1'b0, m} + {9'd0, x1}; // MISTAKE
endmodule
