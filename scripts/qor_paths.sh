#!/bin/sh
# Out-of-context Vivado runs with the worst setup paths (vivado/qor_paths.tcl), several at once on
# the Vivado machine (docker), 4 CPUs each. Designs come from tessera-fpga/build/qor/<name>/ (qorExport).
# Results: build/paths/<tag>/<name>/{paths.tsv,paths.rpt,timing.rpt,util.rpt,vivado.log}, and the QOR lines in
# build/paths/<tag>/qor.txt.
#   scripts/qor_paths.sh <tag> <paths> <period ns> <name> ...
set -e
HOST=${ZED_HOST:-user@zed-host}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TAG=$1; N=$2; P=$3; shift 3
R=tessera-builds/paths/$TAG
ssh $HOST "sudo rm -rf $R && mkdir -p $R"
for d in "$@"; do scp -q -r "$ROOT/tessera-fpga/build/qor/$d" $HOST:$R/; done
scp -q "$ROOT/tessera-fpga/vivado/qor_paths.tcl" $HOST:$R/
i=0
for d in "$@"; do
  cpus="$((i*4))-$((i*4+3))"; i=$(( (i+1) % 8 ))
  files=$(cd "$ROOT/tessera-fpga/build/qor/$d" && ls core.sv top.v 2>/dev/null | tr '\n' ' ')
  ssh $HOST "cd $R/$d && sudo docker run --rm --cpuset-cpus=$cpus -v \$PWD:/w -v \$HOME/$R/qor_paths.tcl:/qor_paths.tcl -w /w kkrizka/vivado:2020.1 bash -c 'source /opt/Xilinx/Vivado/2020.1/settings64.sh; vivado -mode batch -nojournal -nolog -source /qor_paths.tcl -tclargs top clock $P $N $files > vivado.log 2>&1'; grep QOR vivado.log | sed 's/^QOR top/QOR $d/' || echo \"QOR $d FAILED\"" &
done
wait
ssh $HOST "sudo chown -R \$(id -u):\$(id -g) $R"
mkdir -p "$ROOT/build/paths/$TAG"
for d in "$@"; do
  mkdir -p "$ROOT/build/paths/$TAG/$d"
  scp -q $HOST:$R/$d/paths.tsv $HOST:$R/$d/paths.rpt $HOST:$R/$d/timing.rpt $HOST:$R/$d/util.rpt $HOST:$R/$d/vivado.log "$ROOT/build/paths/$TAG/$d/" 2>/dev/null || true
  grep -h '^QOR' "$ROOT/build/paths/$TAG/$d/vivado.log" | sed "s/^QOR top/QOR $d/" || echo "QOR $d FAILED"
done | tee "$ROOT/build/paths/$TAG/qor.txt"
