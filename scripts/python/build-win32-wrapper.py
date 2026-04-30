#!/usr/bin/env python3
"""
Build mppatch_core_wrapper.dll as a PE export forwarding DLL.

Extracts proxy exports from mppatch_core.dll and generates a forwarder DLL
that redirects all exports to the core DLL at the PE loader level.

Usage: python build-win32-wrapper.py <core_dll> <output_def> <output_dll>

This approach avoids the lld GNU-mode COFF import library bug by using
MSVC-mode lld-link with a forwarder DEF file.
"""
import subprocess
import sys
import os
import re


def main():
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <core_dll> <output_def> <output_dll>")
        sys.exit(1)

    core_dll = os.path.abspath(sys.argv[1])
    output_def = os.path.abspath(sys.argv[2])
    output_dll = os.path.abspath(sys.argv[3])
    output_dir = os.path.dirname(output_dll)

    prefix = "mppatch_proxy_CvGameDatabase_"

    # Step 1: Extract proxy exports from mppatch_core.dll using llvm-objdump
    print(f"=== Generating forwarder DEF from {core_dll} ===")
    sys.stdout.flush()
    result = subprocess.run(
        ["llvm-objdump", "-p", core_dll],
        capture_output=True, text=True, check=True
    )

    # Parse the PE export table from llvm-objdump output.
    # Format after "DLL name: mppatch_core.dll" line:
    #   Ordinal base: 1
    #   Ordinal      RVA  Name
    #          1 0x184044  mppatch_proxy_CvGameDatabase_...
    #          2 0x18404c  mppatch_proxy_CvGameDatabase_...
    # ...etc

    lines = result.stdout.split('\n')

    # Find the export table section
    exports = []
    in_exports = False
    dll_name_found = False

    for line in lines:
        stripped = line.strip()

        # Detect start: "DLL name: mppatch_core.dll" followed by header and entries
        if stripped.startswith('DLL name:'):
            dll_name_found = True
            continue

        # After DLL name, skip the header line "Ordinal      RVA  Name"
        if dll_name_found and 'Ordinal' in stripped and 'RVA' in stripped and 'Name' in stripped:
            in_exports = True
            continue

        if in_exports:
            # End at empty line or next section
            if stripped == '':
                break

            # Parse export entry: "      ordinal 0xRVA  name"
            # Use regex to extract ordinal, rva, and name
            parts = stripped.split(None, 2)
            if len(parts) >= 3:
                try:
                    ordinal = int(parts[0])
                    rva = parts[1]
                    name = parts[2]
                    if name.startswith(prefix):
                        short_name = name[len(prefix):]
                        exports.append((short_name, name))
                except (ValueError, IndexError):
                    pass

    print(f"Found {len(exports)} proxy exports")
    sys.stdout.flush()

    if len(exports) == 0:
        print("ERROR: No proxy exports found!")
        print(f"DLL name found: {dll_name_found}")
        sys.exit(1)

    # Step 2: Generate forwarder DEF file
    with open(output_def, 'w') as f:
        f.write('EXPORTS\n')
        for short_name, full_name in exports:
            # Quote names containing @ (special char in DEF files for ordinals)
            q_short = f'"{short_name}"' if '@' in short_name else short_name
            f.write(f'  {q_short} = mppatch_core.{full_name}\n')

    print(f"Generated {len(exports)} forwarder exports in {output_def}")
    sys.stdout.flush()

    # Step 3: Create minimal stub C source (required by lld-link MSVC mode)
    stub_c = os.path.join(output_dir, 'stub.c')
    with open(stub_c, 'w') as f:
        f.write('''// Minimal stub required to produce a PE DLL with lld-link (MSVC mode)
// lld-link requires at least one .obj input file even with /noentry
int __stdcall DllMain(void* hinst, unsigned long reason, void* reserved) {
    return 1;
}
''')

    # Step 4: Compile stub to MSVC COFF format.
    # Use clang-cl if available (Windows), fall back to clang with MSVC target (Linux).
    stub_obj = os.path.join(output_dir, 'stub.obj')
    import shutil
    if shutil.which("clang-cl"):
        print("=== Compiling stub with clang-cl ===")
        sys.stdout.flush()
        subprocess.run(
            ["clang-cl", "--target=i686-pc-windows-msvc", "-c", f"-Fo:{stub_obj}", stub_c],
            check=True
        )
    else:
        print("=== Compiling stub with clang (MSVC target) ===")
        sys.stdout.flush()
        subprocess.run(
            ["clang", "--target=i686-pc-windows-msvc", "-c", stub_c, "-o", stub_obj],
            check=True
        )

    # Step 5: Link with lld-link using the forwarder DEF
    print(f"=== Linking {output_dll} with lld-link ===")
    sys.stdout.flush()
    subprocess.run(
        ["lld-link", "/dll", f"/out:{output_dll}", "/machine:x86",
         "/nodefaultlib", "/noentry", stub_obj, f"/def:{output_def}"],
        check=True
    )

    # Step 6: Strip debug info using llvm-strip (cross-platform) or system strip
    print(f"=== Stripping {output_dll} ===")
    sys.stdout.flush()
    strip_cmd = "llvm-strip" if shutil.which("llvm-strip") else "strip"
    subprocess.run([strip_cmd, output_dll], check=True)

    size = os.path.getsize(output_dll)
    print(f"=== Done: {output_dll} ({size} bytes) ===")
    sys.stdout.flush()


if __name__ == '__main__':
    main()
