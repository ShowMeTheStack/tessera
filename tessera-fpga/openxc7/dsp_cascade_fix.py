#!/usr/bin/env python3
"""nextpnr-xilinx cannot route a constant to a DSP48E1 cascade input (CARRYCASCIN, MULTSIGNIN);
unconnected, the input reads 0 as specified. Removes constant-0 connections of those ports in a
yosys JSON netlist, in place. Usage: dsp_cascade_fix.py soc.json"""
import json, sys

PORTS = ("CARRYCASCIN", "MULTSIGNIN")
path = sys.argv[1]
d = json.load(open(path))
n = 0
for mod in d["modules"].values():
    for cell in mod.get("cells", {}).values():
        if cell["type"] != "DSP48E1":
            continue
        for p in PORTS:
            if cell["connections"].get(p) == ["0"]:
                del cell["connections"][p]
                cell.get("port_directions", {}).pop(p, None)
                n += 1
json.dump(d, open(path, "w"))
print(f"dsp_cascade_fix: removed {n} constant cascade inputs")
