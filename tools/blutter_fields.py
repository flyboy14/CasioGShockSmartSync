#!/usr/bin/env python3
"""Show where each field of a CASIO WATCHES Dart data class lives in its packet.

Run on blutter output (see docs/DW-H5600-DEVELOPMENT.md, "Reading the CASIO app"):
  blutter_fields.py <blutter_out>/asm/casio_watch_lib/public/watch_setting_for_basic_data.dart [field ...]

For every getter/setter (or only the named ones) it prints the instructions that matter:
byte loads (`ldrb [x1, #N]` = data index N, packet index N+1), bit masks (`and w, w, #mask`),
`_getValue` indexes (`mov x2, #N`) and helper calls (Utils::setBCDFromInt, getIntFrom…).
"""
import re, sys

KEEP = re.compile(r"ArrayLoad|ldrb|ldrh|\band\b|ubfx|lsr|orr|tbz|tbnz|cmp +[wx]\d+, #|mov +x\d+, #|bl +#.*;")

def main(path, wanted):
    cur = None
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^  (get|set) _ (\w+=?)\(", line)
        if m:
            cur = m.group(2)
            if not wanted or cur in wanted:
                print(f"## {m.group(1)} {cur}")
            continue
        if cur and (not wanted or cur in wanted) and KEEP.search(line) and "stack_limit" not in line:
            print("   ", line.strip()[:120])

if __name__ == "__main__":
    if len(sys.argv) < 2: sys.exit(__doc__)
    main(sys.argv[1], set(sys.argv[2:]))
