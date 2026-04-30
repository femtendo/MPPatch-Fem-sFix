#!/usr/bin/env python3
"""
Generate cleaned DEF file (without quotes) and underscore-prefixed DEF file.

Usage: python gen-cleaned-def.py <input.def> <output_dir>
"""
import re
import sys
import os


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.def> <output_dir>")
        sys.exit(1)

    input_path = sys.argv[1]
    output_dir = sys.argv[2]

    with open(input_path, 'r') as f:
        lines = f.readlines()

    print(f'Read {len(lines)} lines from {input_path}')

    cleaned_lines = []
    underscore_lines = []
    entry_count = 0

    for line in lines:
        m = re.search(r'("[^"]*mppatch_proxy_CvGameDatabase_[^"]*")', line)
        if m:
            entry_count += 1
            quoted = m.group(1)
            unquoted = quoted.strip('"')
            cleaned_line = line.replace(quoted, unquoted, 1)
            underscore_line = cleaned_line.replace(unquoted, '_' + unquoted, 1)
            cleaned_lines.append(cleaned_line)
            underscore_lines.append(underscore_line)
        else:
            cleaned_lines.append(line)
            underscore_lines.append(line)

    print(f'Export entries found: {entry_count}')

    clean_path = os.path.join(output_dir, 'mppatch_core_clean.def')
    with open(clean_path, 'w') as f:
        f.writelines(cleaned_lines)
    print(f'Written: {clean_path}')

    underscore_path = os.path.join(output_dir, 'mppatch_core_underscore.def')
    with open(underscore_path, 'w') as f:
        f.writelines(underscore_lines)
    print(f'Written: {underscore_path}')


if __name__ == '__main__':
    main()
