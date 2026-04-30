#!/usr/bin/env python3
"""
Generate NASM wrapper assembly from a .def file containing proxy exports.

Usage: python gen-wrapper-asm-from-def.py path/to/list.def > wrapper.s

The .def file contains lines like:
  "mppatch_proxy_CvGameDatabase_??0BinaryIO@Database@@QAE@PBD@Z"

This generates NASM assembly that forwards each symbol to the proxy implementation.
"""

import re
import sys

def main():
    if len(sys.argv) < 2:
        print("Usage: gen-wrapper-asm-from-def.py <list.def>", file=sys.stderr)
        sys.exit(1)

    def_path = sys.argv[1]
    prefix = "mppatch_proxy_CvGameDatabase_"

    # Read all export symbols from def file
    with open(def_path, 'r') as f:
        lines = f.readlines()

    # Parse lines that contain our proxy prefix
    syms = []
    for line in lines:
        line = line.strip()
        # Match: "mppatch_proxy_CvGameDatabase_<symbol>"
        if prefix in line:
            # Extract the full symbol with quotes
            m = re.search(r'"' + re.escape(prefix) + r'([^"]+)"', line)
            if m:
                original_sym = m.group(1)  # Just the original symbol name
                syms.append(original_sym)

    if not syms:
        print(f"Error: No proxy symbols found in {def_path}", file=sys.stderr)
        sys.exit(1)

    print("segment .text")
    for sym in syms:
        print(f"global _{sym}")
        print(f"extern _{prefix}{sym}")
        print(f"_{sym}: jmp _{prefix}{sym}")
        print(f"export {sym}")

if __name__ == "__main__":
    main()
