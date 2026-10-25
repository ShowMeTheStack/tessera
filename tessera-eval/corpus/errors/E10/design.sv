typedef enum logic { RED, GREEN } light_t;
typedef enum logic { OPEN, SHUT } door_t;
module dut(input light_t a, input door_t b, output light_t y);
  assign y = b; // MISTAKE
endmodule
