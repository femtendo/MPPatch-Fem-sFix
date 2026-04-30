#!/usr/bin/env python3
"""
Build lua51.dll as a PE export forwarding DLL to lua51_Win32.dll.

Extracts exports from luajit_win32.dll (or any Lua 5.1 compatible DLL) and
generates a forwarder DLL that redirects all exports to lua51_Win32.dll
at the PE loader level.

Usage: python build-lua51-forwarder.py <source_dll> <output_def> <output_dll>
"""
import subprocess
import sys
import os


def main():
    if len(sys.argv) != 4:
        print(f"Usage: {sys.argv[0]} <source_dll> <output_def> <output_dll>")
        sys.exit(1)

    source_dll = os.path.abspath(sys.argv[1])
    output_def = os.path.abspath(sys.argv[2])
    output_dll = os.path.abspath(sys.argv[3])
    output_dir = os.path.dirname(output_dll)

    target_name = "lua51_Win32"  # Forward target at runtime

    # Step 1: Extract exports from source DLL using llvm-objdump
    print(f"=== Extracting exports from {source_dll} ===")
    sys.stdout.flush()
    result = subprocess.run(
        ["llvm-objdump", "-p", source_dll],
        capture_output=True, text=True, check=True
    )

    lines = result.stdout.split('\n')
    exports = []
    in_exports = False

    for line in lines:
        stripped = line.strip()

        # Detect the export table: look for "Ordinal      RVA  Name" header
        if 'Ordinal' in stripped and 'RVA' in stripped and 'Name' in stripped:
            in_exports = True
            continue

        if in_exports:
            # End at blank line (next section boundary)
            if stripped == '':
                break

            # Parse: "      ordinal 0xRVA  name"
            parts = stripped.split(None, 2)
            if len(parts) >= 3:
                try:
                    ordinal = int(parts[0])
                    name = parts[2]
                    exports.append(name)
                except (ValueError, IndexError):
                    pass

    print(f"Found {len(exports)} exports")
    sys.stdout.flush()

    if len(exports) == 0:
        print("ERROR: No exports found!")
        sys.exit(1)

    # Step 2: Generate forwarder DEF file
    # Forward each export to the same name in lua51_Win32.dll
    with open(output_def, 'w') as f:
        f.write('EXPORTS\n')
        for name in exports:
            # Quote names containing @ to prevent DEF parser confusion
            q_name = f'"{name}"' if '@' in name else name
            f.write(f'  {q_name} = {target_name}.{name}\n')

    print(f"Generated {len(exports)} forwarder exports in {output_def}")
    sys.stdout.flush()

    # Step 3: Create minimal stub C source (required by lld-link MSVC mode)
    stub_c = os.path.join(output_dir, 'stub.c')
    with open(stub_c, 'w') as f:
        f.write('''// Minimal stub required to produce a PE DLL with lld-link (MSVC mode)
int __stdcall DllMain(void* hinst, unsigned long reason, void* reserved) {
    return 1;
}
''')

    # Step 4: Compile stub to MSVC COFF format.
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
