#!/bin/bash

#
# Copyright (c) 2015-2023 Lymia Kanokawa <lymia@lymia.moe>
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.
#

CORE_DLL="$1"
WRAPPER_DEF="$2"
WRAPPER_DLL="$3"
DIR=$(dirname "$CORE_DLL")

if [ -z "$CORE_DLL" ] || [ -z "$WRAPPER_DEF" ] || [ -z "$WRAPPER_DLL" ]; then
  echo "Usage: $0 <core_dll> <wrapper_def> <wrapper_dll>"
  echo "  core_dll:     path to mppatch_core.dll"
  echo "  wrapper_def:  output path for generated forwarder .def file"
  echo "  wrapper_dll:  output path for generated wrapper dll"
  exit 1
fi

prefix="mppatch_proxy_CvGameDatabase_"

echo "=== Generating forwarder DEF from $CORE_DLL ==="

# Extract exports from mppatch_core.dll using llvm-objdump
# Filter for proxy functions, extract just the name

cat > "$WRAPPER_DEF" << 'EXPORTS_HEADER'
EXPORTS
EXPORTS_HEADER

# Use llvm-objdump to get the export table, extract function names with the proxy prefix
# Output format after "Ordinal  RVA  Name" header:
#       1 0x184044  mppatch_proxy_CvGameDatabase_0000
#       2 0x18404c  mppatch_proxy_CvGameDatabase_0001
llvm-objdump -p "$CORE_DLL" | awk '
  /Ordinal[[:space:]]+RVA[[:space:]]+Name/ { p=1; next }
  p && /^[[:space:]]*$/ { exit }
  p { print $NF }
' | while read -r name; do
  case "$name" in
    ${prefix}*)
      short_name="${name#$prefix}"
      echo "  \"$short_name\" = mppatch_core.$name" >> "$WRAPPER_DEF"
      ;;
  esac
done

count=$(grep -c "= mppatch_core\." "$WRAPPER_DEF" 2>/dev/null || echo 0)
echo "Generated $count forwarder exports in $WRAPPER_DEF"

echo "=== Building forwarder DLL ==="

# Create a minimal stub source file
STUB_C="${DIR}/stub.c"
cat > "$STUB_C" << 'STUB_EOF'
// Minimal stub required to produce a PE DLL with lld-link (MSVC mode)
// lld-link requires at least one .obj input file even with /noentry
int __stdcall DllMain(void* hinst, unsigned long reason, void* reserved) {
    return 1;
}
STUB_EOF

# Compile stub with clang-cl (MSVC COFF format)
STUB_OBJ="${DIR}/stub.obj"
clang-cl --target=i686-pc-windows-msvc -c -Fo:"$STUB_OBJ" "$STUB_C" || exit 1

# Link with lld-link using the forwarder DEF
lld-link /dll /out:"$WRAPPER_DLL" /machine:x86 /nodefaultlib /noentry "$STUB_OBJ" /def:"$WRAPPER_DEF" || exit 1

echo "=== Stripping $WRAPPER_DLL ==="
strip "$WRAPPER_DLL" || exit 1

echo "=== Done: $WRAPPER_DLL ==="
