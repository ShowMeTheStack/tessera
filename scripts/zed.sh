#!/bin/sh
# ZedBoard flow on the machine the board is attached to (Vivado 2020.1 in docker there).
#   scripts/zed.sh build <core> [fclk_MHz]    emit SV here, build soc.bit/soc.xsa there
#   scripts/zed.sh openxc7 <core> [fclk_MHz]  the same SoC with yosys + nextpnr-xilinx + prjxray there
#   scripts/zed.sh xsct <script.tcl> [args]   run xsct (JTAG) there, in the build directory
# ZED_HOST (default user@zed-host), ZED_DIR (default ~/tessera-zed on that host).
set -e
HOST=${ZED_HOST:-user@zed-host}
RDIR=${ZED_DIR:-tessera-zed}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
IMG=kkrizka/vivado:2020.1
VIV="source /opt/Xilinx/Vivado/2020.1/settings64.sh; export DISPLAY=:99"
cmd=$1; shift
case $cmd in
build)
  core=$1; fclk=${2:-50}
  "$ROOT/gradlew" -q -p "$ROOT" :tessera-fpga:run --args="$core $ROOT/build/zed/$core"
  ssh $HOST "mkdir -p $RDIR/$core"
  scp -q "$ROOT/build/zed/$core/tessera_soc.sv" "$ROOT/tessera-fpga/vivado/zed_soc.tcl" $HOST:$RDIR/$core/
  ssh $HOST "cd $RDIR/$core && sudo docker run --rm --cpuset-cpus=${ZED_CPUS:-0-15} -v \$PWD:/w -w /w $IMG bash -c '$VIV; vivado -mode batch -nojournal -nolog -source zed_soc.tcl -tclargs $fclk > vivado.log 2>&1; tail -3 vivado.log'; sudo chown -R \$(id -u):\$(id -g) ."
  ssh $HOST "cd $RDIR/$core && grep -E 'WNS|TESSERA_BUILD_DONE|ERROR' vivado.log | head; grep -A12 '| Slice LUTs' utilization.rpt | head -14"
  ;;
openxc7)
  # the same SoC with the open-source flow; the PS7 setup (ps7_init.tcl, which sets FCLK0, and soc.xsa)
  # comes from a Vivado build: of <core>, or of PS7_FROM (e.g. a build whose FCLK0 matches fclk)
  core=$1; fclk=${2:-50}; d=$core-openxc7; ps=${PS7_FROM:-$core}
  "$ROOT/gradlew" -q -p "$ROOT" :tessera-fpga:run --args="$core $ROOT/build/zed/$core"
  ssh $HOST "mkdir -p $RDIR/$d"
  scp -q "$ROOT/build/zed/$core/tessera_soc.sv" "$ROOT"/tessera-fpga/openxc7/{zed_top.v,zedboard.xdc,build.sh,lutrams_nextpnr.txt,dsp_cascade_fix.py} $HOST:$RDIR/$d/
  # C1a: nextpnr-xilinx cannot pack the single-port LUT RAM yosys picks for its memories, and the block RAM
  # yosys infers for its instruction memory (program counter merged into the address register) fails on
  # the board: map both memories with the restricted LUT-RAM library (tessera-fpga/openxc7/lutrams_nextpnr.txt)
  case $core in C1a|C1A) mems="top/soc.core.dmem top/soc.core.imem";; *) mems="";; esac
  ssh $HOST "cp $RDIR/$ps/ps7_init.tcl $RDIR/$ps/soc.xsa $RDIR/$d/ && LUTRAM_MEMS='$mems' bash $RDIR/$d/build.sh $RDIR/$d $fclk"
  ;;
xsct)
  dir=$1; script=$2; shift 2
  scp -q "$script" $HOST:$RDIR/$dir/
  ssh $HOST "cd $RDIR/$dir && sudo docker run --rm --privileged --net=host -v /dev/bus/usb:/dev/bus/usb -v \$PWD:/w -w /w $IMG bash -c '$VIV; export PATH=/opt/Xilinx/Vitis/2020.1/bin:\$PATH; xsct $(basename $script) $*'; sudo chown -R \$(id -u):\$(id -g) ."
  ;;
*) echo "usage: $0 build <core> [fclk] | xsct <dir> <script.tcl> [args]"; exit 2 ;;
esac
