#!/usr/bin/env bash
# Open-source ZedBoard build, run on the host with openXC7: tessera_soc.sv + zed_top.v -> soc.bit
#   build.sh <dir with tessera_soc.sv> [fclk MHz]
set -euo pipefail
cd "$1"; FREQ=${2:-50}
NP=${NEXTPNR_XILINX:-$HOME/nextpnr-xilinx/build-ninja/nextpnr-xilinx}
CHIPDB=${CHIPDB:-$HOME/nextpnr-xilinx/xilinx/xc7z020.bin}
DB=${PRJXRAY_DB:-$HOME/nextpnr-xilinx/xilinx/external/prjxray-db/zynq7}
XRAY=${PRJXRAY:-$HOME/work/prjxray}
PART=xc7z020clg484-1
SX="synth_xilinx -flatten -abc9 -arch xc7 -top top"
# LUTRAM_MEMS: memories (e.g. top.soc.core.dmem) to map to LUT RAM that nextpnr-xilinx can pack, before the rest
PRE=""; for m in ${LUTRAM_MEMS:-}; do PRE="$PRE memory_libmap -lib lutrams_nextpnr.txt $m;"; done
[ -n "$PRE" ] && PRE="$SX -run :map_memory; $PRE techmap -map +/xilinx/lutrams_xc5v_map.v; $SX -run map_memory:;" || PRE="$SX;"
yosys -q -l yosys.log -p "read_verilog -sv tessera_soc.sv; read_verilog zed_top.v; $PRE write_json soc.json"
python3 dsp_cascade_fix.py soc.json
grep -A40 "Printing statistics" yosys.log | grep -E "LUT|FDRE|FDSE|RAMB|DSP|CARRY" | head -20 || true
"$NP" ${NEXTPNR_SEED:+--seed $NEXTPNR_SEED} --chipdb "$CHIPDB" --xdc zedboard.xdc --json soc.json --write soc_routed.json --fasm soc.fasm --freq "$FREQ" > nextpnr.log 2>&1
grep -E "Max frequency|Device utilisation" -A0 nextpnr.log | tail -3
source "$XRAY/.venv/bin/activate"
PYTHONPATH="$XRAY:${PYTHONPATH:-}" python3 "$XRAY/utils/fasm2frames.py" --part $PART --db-root "$DB" soc.fasm > soc.frames 2> fasm2frames.log
xc7frames2bit --part_file "$DB/$PART/part.yaml" --part_name $PART --frm_file soc.frames --output_file soc.bit
ls -la soc.bit
