#!/usr/bin/env python3
#  Copyright (c) 2015-2023 Lymia Kanokawa <lymia@lymia.moe>
#
#  Permission is hereby granted, free of charge, to any person obtaining a copy
#  of this software and associated documentation files (the "Software"), to deal
#  in the Software without restriction, including without limitation the rights
#  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
#  copies of the Software, and to permit persons to whom the Software is
#  furnished to do so, subject to the following conditions:
#
#  The above copyright notice and this permission notice shall be included in
#  all copies or substantial portions of the Software.
#
#  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
#  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
#  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
#  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
#  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
#  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
#  THE SOFTWARE.

"""
Extract version info from a Civilization V DLL for use in versions.rs.

Computes the SHA-256 hash, locates the lGetMemoryUsage export, and attempts to
find the SetActiveDLCAndMods call-site offsets using byte-pattern matching
against a known reference DLL.

Usage:
  # Full analysis with reference DLL (recommended):
  python3 extract-version-info.py new.dll --ref-dll old.dll \\
      --ref-offsets dx9=0x006CD160,dx11=0x006B8E50,tablet=0x0065DC10

  # Just hash + exports (no offset search):
  python3 extract-version-info.py new.dll

Outputs a Rust VersionInfo entry ready to paste into versions.rs.
"""

import hashlib
import struct
import sys
import argparse
import os.path


# PE constants
IMAGE_DOS_SIGNATURE = 0x5A4D
IMAGE_NT_SIGNATURE = 0x00004550
IMAGE_DIRECTORY_ENTRY_EXPORT = 0
IMAGE_SIZEOF_SHORT_NAME = 8


def read_u16(data, offset):
    return struct.unpack_from("<H", data, offset)[0]


def read_u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def rva_to_offset(sections, rva):
    """Convert an RVA to a file offset using section headers."""
    for name, virtual_address, size_of_raw, pointer_to_raw in sections:
        if virtual_address <= rva < virtual_address + size_of_raw:
            return (rva - virtual_address) + pointer_to_raw
    return None


def parse_pe(data):
    """Parse PE header, return (sections, export_dir_rva, export_dir_size)."""
    if len(data) < 64:
        raise ValueError("File too small to be a PE")

    if read_u16(data, 0) != IMAGE_DOS_SIGNATURE:
        raise ValueError("Not a valid PE file (missing MZ signature)")

    pe_offset = read_u32(data, 0x3C)
    if read_u32(data, pe_offset) != IMAGE_NT_SIGNATURE:
        raise ValueError("Not a valid PE file (missing PE signature)")

    # PE optional header starts at pe_offset + 24
    optional_header = pe_offset + 24
    magic = read_u16(data, optional_header)

    # PE32 (0x10B) or PE32+ (0x20B)
    if magic == 0x10B:
        # PE32: export dir RVA at optional_header + 96
        # number of data directories at optional_header + 92
        num_dirs = read_u32(data, optional_header + 92)
        export_rva = read_u32(data, optional_header + 96)
        export_size = read_u32(data, optional_header + 100)
        # section headers start after optional header
        # PE32 optional header is 224 bytes
        section_offset = optional_header + 224
    elif magic == 0x20B:
        num_dirs = read_u32(data, optional_header + 108)
        export_rva = read_u32(data, optional_header + 112)
        export_size = read_u32(data, optional_header + 116)
        section_offset = optional_header + 240
    else:
        raise ValueError(f"Unknown PE magic: 0x{magic:04X}")

    # Read number of sections from file header (pe_offset + 6)
    num_sections = read_u16(data, pe_offset + 6)

    sections = []
    for i in range(num_sections):
        off = section_offset + i * 40
        name_raw = data[off : off + IMAGE_SIZEOF_SHORT_NAME]
        name = name_raw.rstrip(b"\x00").decode("ascii", errors="replace")
        virtual_size = read_u32(data, off + 8)
        virtual_address = read_u32(data, off + 12)
        size_of_raw = read_u32(data, off + 16)
        pointer_to_raw = read_u32(data, off + 20)
        sections.append((name, virtual_address, max(size_of_raw, virtual_size), pointer_to_raw))

    return sections, export_rva, export_size


def parse_exports(data, sections, export_rva, export_size):
    """Parse the export directory and return {name: RVA} dict."""
    if export_rva == 0 or export_size == 0:
        return {}

    export_off = rva_to_offset(sections, export_rva)
    if export_off is None:
        return {}

    # Export directory structure
    num_names = read_u32(data, export_off + 24)
    func_count = read_u32(data, export_off + 20)
    addr_table_rva = read_u32(data, export_off + 28)
    name_table_rva = read_u32(data, export_off + 32)
    ordinal_table_rva = read_u32(data, export_off + 36)

    addr_off = rva_to_offset(sections, addr_table_rva)
    name_off = rva_to_offset(sections, name_table_rva)
    ord_off = rva_to_offset(sections, ordinal_table_rva)

    if addr_off is None or name_off is None or ord_off is None:
        return {}

    exports = {}
    for i in range(num_names):
        name_rva = read_u32(data, name_off + i * 4)
        ordinal = read_u16(data, ord_off + i * 2)
        func_rva = read_u32(data, addr_off + ordinal * 4)

        name_file_off = rva_to_offset(sections, name_rva)
        if name_file_off is None:
            continue

        end = data.find(b"\x00", name_file_off)
        func_name = data[name_file_off:end].decode("ascii", errors="replace")
        exports[func_name] = func_rva

    return exports


def compute_sha256(filepath):
    """Compute SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()


def extract_call_bytes(data, sections, binary_base, call_rva, pre_bytes=16, post_bytes=16):
    """
    Extract byte signature around a call site.
    Returns (pattern_bytes, pattern_offset_in_dll).
    The CALL instruction (5 bytes: E8 + 4-byte rel32) is included but the
    rel32 displacement bytes will be masked in the pattern.
    """
    call_off = rva_to_offset(sections, call_rva - binary_base)
    if call_off is None:
        return None, None

    start = call_off - pre_bytes
    end = call_off + 5 + post_bytes
    if start < 0 or end > len(data):
        return None, None

    raw = data[start:end]
    # Create mask: 0 = match byte exactly, 1 = wildcard (don't care)
    mask = bytearray(len(raw))
    # The 4 bytes of the CALL displacement (offset pre_bytes+1 through pre_bytes+4) are wildcards
    for i in range(len(raw)):
        if pre_bytes + 1 <= i < pre_bytes + 5:
            mask[i] = 1
        else:
            mask[i] = 0

    return bytes(raw), bytes(mask), start


def search_pattern(data, pattern, mask):
    """Search for a byte pattern with mask in data. Returns list of file offsets."""
    results = []
    plen = len(pattern)
    for i in range(len(data) - plen + 1):
        match = True
        for j in range(plen):
            if mask[j] == 0 and data[i + j] != pattern[j]:
                match = False
                break
        if match:
            results.append(i)
    return results


def find_call_offset(data, sections, binary_base, pattern, mask):
    """
    Search for a byte pattern in data, return the RVA of the matching CALL instruction.
    The pattern has the CALL at offset `pre_bytes` within the pattern.
    """
    # Determine pre_bytes: the CALL is after the pre_bytes prefix in the pattern
    # Find the E8 byte in the pattern (it's at the position just before the masked bytes)
    call_offset_in_pattern = None
    for i in range(len(pattern)):
        if mask[i] == 0 and pattern[i] == 0xE8:
            # Check if the next 4 bytes are wildcards
            if all(mask[i + j] == 1 for j in range(1, 5)):
                call_offset_in_pattern = i
                break

    if call_offset_in_pattern is None:
        # Fallback: assume the CALL starts at pre_bytes
        call_offset_in_pattern = 16  # default pre_bytes

    results = search_pattern(data, pattern, mask)
    if not results:
        return None

    if len(results) > 1:
        print(f"    Warning: found {len(results)} matches, using first at file offset 0x{results[0]:X}")

    # Convert file offset of match back to RVA
    match_file_off = results[0]
    call_file_off = match_file_off + call_offset_in_pattern

    # Find which section this is in
    for name, va, size, ptr_raw in sections:
        if ptr_raw <= call_file_off < ptr_raw + size:
            rva = (call_file_off - ptr_raw) + va
            return rva + binary_base

    # Fallback: assume it maps linearly
    return call_file_off + binary_base


def find_lua_export(exports):
    """Find the lGetMemoryUsage export and return (name, rva)."""
    for name, rva in exports.items():
        if "lGetMemoryUsage" in name:
            return name, rva
    return None, None


def va_to_rva(va, binary_base=0x00400000):
    return va - binary_base


def rva_to_va(rva, binary_base=0x00400000):
    return rva + binary_base


def format_rust_entry(sha256, platform, lua_sym, dx9_info, dx11_info, tablet_info, binary_base_hex):
    """Format a VersionInfo entry as Rust code."""
    name = {
        "win32": "Civilization V / 1.0.3.279 / Win32 + Steam",
        "linux": "Civilization V / 1.0.3.279 / Linux + Steam",
    }.get(platform, f"Civilization V / ??? / {platform} + Steam")

    if platform == "win32":
        lines = []
        lines.append(f'        "{sha256}" => VersionInfo {{')
        lines.append(f'            name: "{name}",')
        lines.append(f"            platform: Platform::Win32,")
        lines.append(
            f'            sym_lGetMemoryUsage: SymbolInfo::DllProxy(ProxySource::CvGameDatabase, "{lua_sym}"),'
        )
        lines.append(f"            sym_SetActiveDLCAndMods: SymbolInfo::Win32Offsets(SymWin32Offsets {{")
        lines.append(f'                name: "SetActiveDLCAndMods",')
        lines.append(f"                dx9: (0x{dx9_info[0]:08X}, {dx9_info[1]}),")
        lines.append(f"                dx11: (0x{dx11_info[0]:08X}, {dx11_info[1]}),")
        lines.append(f"                tablet: (0x{tablet_info[0]:08X}, {tablet_info[1]}),")
        lines.append(f"            }}),")
        lines.append(f"            binary_base: 0x{binary_base_hex:08X},")
        lines.append(f"        }},")
        return "\n".join(lines)
    elif platform == "linux":
        lines = []
        lines.append(f'        "{sha256}" => VersionInfo {{')
        lines.append(f'            name: "{name}",')
        lines.append(f"            platform: Platform::Linux,")
        lines.append(f'            sym_lGetMemoryUsage: SymbolInfo::PublicNamed("{lua_sym}", 7),')
        lines.append(
            f'            sym_SetActiveDLCAndMods: SymbolInfo::PublicNamed("{lua_sym}", 10),'
        )  # placeholder
        lines.append(f"            binary_base: 0x{binary_base_hex:08X},")
        lines.append(f"        }},")
        return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(
        description="Extract Civ5 version info from a DLL for versions.rs"
    )
    parser.add_argument("dll", help="Path to the target DLL (e.g., CvGameDatabaseWin32Final Release.dll)")
    parser.add_argument(
        "--ref-dll", help="Path to a known-good reference DLL for byte-pattern extraction"
    )
    parser.add_argument(
        "--ref-offsets",
        help="Comma-separated call-site VAs for reference DLL, e.g. dx9=0x006CD160,dx11=0x006B8E50,tablet=0x0065DC10",
    )
    parser.add_argument(
        "--binary-base",
        default="0x00400000",
        help="Binary base address (default: 0x00400000)",
    )
    parser.add_argument(
        "--platform",
        default="win32",
        choices=["win32", "linux"],
        help="Target platform (default: win32)",
    )
    parser.add_argument(
        "--patch-bytes",
        type=int,
        default=6,
        help="Number of bytes to overwrite at call site (default: 6)",
    )
    args = parser.parse_args()

    binary_base = int(args.binary_base, 16)

    if not os.path.exists(args.dll):
        print(f"Error: file not found: {args.dll}", file=sys.stderr)
        sys.exit(1)

    with open(args.dll, "rb") as f:
        data = f.read()

    # --- SHA-256 ---
    sha256 = compute_sha256(args.dll)
    print(f"SHA-256: {sha256}")
    print()

    # --- PE parsing ---
    try:
        sections, export_rva, export_size = parse_pe(data)
    except ValueError as e:
        print(f"Error parsing PE: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Sections: {len(sections)}")
    for name, va, size, ptr in sections:
        print(f"  {name}: VA=0x{va:08X} size=0x{size:X} file=0x{ptr:X}")
    print()

    # --- Exports ---
    exports = parse_exports(data, sections, export_rva, export_size)
    print(f"Exports: {len(exports)} named symbols")
    lua_name, lua_rva = find_lua_export(exports)
    if lua_name:
        print(f"  lGetMemoryUsage: {lua_name} @ RVA 0x{lua_rva:08X}")
    else:
        print("  lGetMemoryUsage: NOT FOUND in exports!")
    print()

    # --- Call-site offset search ---
    dx9_va = None
    dx11_va = None
    tablet_va = None

    if args.ref_dll and args.ref_offsets:
        if not os.path.exists(args.ref_dll):
            print(f"Error: reference DLL not found: {args.ref_dll}", file=sys.stderr)
            sys.exit(1)

        with open(args.ref_dll, "rb") as f:
            ref_data = f.read()

        ref_sections, _, _ = parse_pe(ref_data)

        ref_offsets = {}
        for item in args.ref_offsets.split(","):
            key, val = item.strip().split("=")
            ref_offsets[key.strip()] = int(val.strip(), 16)

        print("--- Searching for call-site offsets using reference patterns ---")
        print()

        for variant in ("dx9", "dx11", "tablet"):
            ref_va = ref_offsets.get(variant)
            if ref_va is None:
                print(f"  {variant}: no reference offset provided, skipping")
                continue

            ref_rva = ref_va - binary_base
            pattern, mask, _ = extract_call_bytes(ref_data, ref_sections, binary_base, ref_va)
            if pattern is None:
                print(f"  {variant}: could not extract pattern at 0x{ref_va:08X}")
                continue

            print(f"  {variant}: pattern={pattern.hex()} mask={mask.hex()}")

            new_va = find_call_offset(data, sections, binary_base, pattern, mask)
            if new_va:
                rva = new_va - binary_base
                print(f"  {variant}: MATCH at VA 0x{new_va:08X} (RVA 0x{rva:08X})")
                if variant == "dx9":
                    dx9_va = new_va
                elif variant == "dx11":
                    dx11_va = new_va
                elif variant == "tablet":
                    tablet_va = new_va
            else:
                print(f"  {variant}: NO MATCH FOUND")
        print()

    # --- Output Rust code ---
    print("--- Rust VersionInfo entry ---")
    print()

    if args.platform == "win32":
        if dx9_va is None:
            dx9_va = 0x00000000  # placeholder, user must fill in
        if dx11_va is None:
            dx11_va = 0x00000000
        if tablet_va is None:
            tablet_va = 0x00000000

        if not lua_name:
            lua_name = "?lGetMemoryUsage@Lua@Scripting@Database@@SAHPAUlua_State@@@Z"
            print("// WARNING: lGetMemoryUsage export not found, using known symbol name as fallback")

        print(format_rust_entry(
            sha256, args.platform, lua_name,
            (dx9_va, args.patch_bytes),
            (dx11_va, args.patch_bytes),
            (tablet_va, args.patch_bytes),
            binary_base,
        ))

        if dx9_va == 0 or dx11_va == 0 or tablet_va == 0:
            print()
            print("// WARNING: Some offsets are 0x00000000 placeholders.")
            print("// Run with --ref-dll and --ref-offsets from a known version to auto-resolve,")
            print("// or manually find the SetActiveDLCAndMods call sites with Ghidra/IDA.")
            print("// Each is a 6-byte CALL instruction site in the .text section.")

    elif args.platform == "linux":
        if not lua_name:
            lua_name = "_ZN8Database9Scripting3Lua15lGetMemoryUsageEP9lua_State"
            print("// WARNING: lGetMemoryUsage export not found, using known symbol name as fallback")

        print(format_rust_entry(
            sha256, args.platform, lua_name,
            (0, args.patch_bytes), (0, args.patch_bytes), (0, args.patch_bytes),
            binary_base,
        ))
        print()
        print("// NOTE: Linux uses PublicNamed symbols. Verify the mangled names with 'nm' or 'objdump'.")


if __name__ == "__main__":
    main()
