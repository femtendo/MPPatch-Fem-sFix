#!/usr/bin/env python3
"""
Generate a DEF file with forward exports for the wrapper DLL.
Each entry forwards to mppatch_core.dll's proxy function.

Usage: python gen-forwarder-def.py <input.def> <output.def>
"""
import re
import sys


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.def> <output.def>")
        sys.exit(1)

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    with open(input_path, 'r') as f:
        lines = f.readlines()

    def_lines = ['EXPORTS\n']
    count = 0
    for line in lines:
        m = re.search(r'"mppatch_proxy_CvGameDatabase_([^"]+)"', line)
        if m:
            short_name = m.group(1)
            def_lines.append(f'  "{short_name}" = mppatch_core.mppatch_proxy_CvGameDatabase_{short_name}\n')
            count += 1

    with open(output_path, 'w') as f:
        f.writelines(def_lines)

    print(f'Generated {count} forward exports in {output_path}')
    if count > 0:
        print(f'First: {def_lines[1].strip()}')
        print(f'Last: {def_lines[-1].strip()}')


if __name__ == '__main__':
    main()
