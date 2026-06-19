// A tiny synchronous counter — just enough RTL to drive a real synthesis run
// through the FPGA toolchain inside the container.
module counter #(
    parameter WIDTH = 8
) (
    input  wire             clk,
    input  wire             rst_n,
    input  wire             en,
    output reg  [WIDTH-1:0] count
);
  always @(posedge clk or negedge rst_n) begin
    if (!rst_n)      count <= {WIDTH{1'b0}};
    else if (en)     count <= count + 1'b1;
  end
endmodule
