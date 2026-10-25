#!/bin/sh
# Out-of-context resources and Fmax (Vivado 2020.1, xc7z020clg484-1) for the Tessera cores and
# baseline cores, on the machine with Vivado (docker). Results: build/qor/results.txt
#   scripts/qor.sh [period ns]     (baselines' sources must be in tessera-fpga/build/qor/<name>/)
set -e
HOST=${ZED_HOST:-user@zed-host}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
P=${1:-5}
"$ROOT/gradlew" -q -p "$ROOT" :tessera-fpga:qorExport
ssh $HOST "sudo rm -rf tessera-zed/qor && mkdir -p tessera-zed/qor"
scp -q -r "$ROOT"/tessera-fpga/build/qor/* "$ROOT/tessera-fpga/vivado/qor.tcl" $HOST:tessera-zed/qor/
# name top clock files
JOBS="tessera_sched3:top:clock:core.sv,top.v tessera_sched4:top:clock:core.sv,top.v tessera_sched5:top:clock:core.sv,top.v
tessera_sched6:top:clock:core.sv,top.v tessera_ooo:top:clock:core.sv,top.v tessera_fpga5:top:clock:core.sv,top.v
tessera_ooo_bram:top:clock:core.sv,top.v vexriscv:top:clk:core.v,top.v nerv:nerv:clock:core.sv
picorv32:top:clk:core.v,top.v picorv32_im:top:clk:core.v,top.v"
i=0
for j in $JOBS; do
  name=${j%%:*}; rest=${j#*:}; top=${rest%%:*}; rest=${rest#*:}; clk=${rest%%:*}; files=$(echo ${rest#*:} | tr ',' ' ')
  cpus="$((i*2))-$((i*2+1))"; i=$((i+1))
  ssh $HOST "cd tessera-zed/qor/$name && sudo docker run --rm --cpuset-cpus=$cpus -v \$PWD:/w -v \$HOME/tessera-zed/qor/qor.tcl:/qor.tcl -w /w kkrizka/vivado:2020.1 bash -c 'source /opt/Xilinx/Vivado/2020.1/settings64.sh; vivado -mode batch -nojournal -nolog -source /qor.tcl -tclargs $top $clk $P $files > vivado.log 2>&1'; grep QOR vivado.log || (echo \"$name FAILED\"; grep ERROR vivado.log | head -3)" &
done
wait
ssh $HOST "sudo chown -R \$(id -u):\$(id -g) tessera-zed/qor; grep -h '^QOR' tessera-zed/qor/*/vivado.log" | tee "$ROOT/build/qor-results.txt"
