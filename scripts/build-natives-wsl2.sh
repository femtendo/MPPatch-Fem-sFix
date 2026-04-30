#!/bin/bash
#
# Copyright (c) 2015-2024 Lymia Kanokawa <lymia@lymia.moe>
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

# Run inside WSL2 to build MPPatch native binaries (Rust + LuaJIT) for Win32 and Linux.
# Output lands in target/native-bin/ — ready for sbt nativeImage on Windows.
#
# Usage: bash scripts/build-natives-wsl2.sh [--output-dir <path>]

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ---- Parse args ----

OUTPUT_DIR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output-dir)
      OUTPUT_DIR="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: $0 [--output-dir <path>]"
      echo "  --output-dir  Copy native-bin/ output to this directory after build"
      echo "                (default: copy to repo's own target/native-bin/)"
      exit 0 ;;
    *)
      error "Unknown option: $1"
      exit 1 ;;
  esac
done

# ---- Preflight ----

if ! grep -qi "microsoft" /proc/sys/kernel/osrelease 2>/dev/null; then
  error "This script must be run inside WSL."
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

info "Repo root: $REPO_ROOT"
cd "$REPO_ROOT"

if ! command -v sbt &>/dev/null; then
  error "sbt not found. Run scripts/setup-wsl2.sh first."
  exit 1
fi

# ---- Initialize submodule ----

if [ ! -f src/patch/luajit/Makefile ]; then
  info "Initializing LuaJIT submodule..."
  git submodule update --init --recursive
fi

# ---- Build ----

info "Building native binaries (Rust + LuaJIT + wrapper DLLs)..."
info "This cross-compiles for i686-pc-windows-gnu and i686-unknown-linux-gnu."

sbt buildNative

# ---- Verify output ----

NATIVE_BIN="$REPO_ROOT/target/native-bin"

info "Verifying build output in $NATIVE_BIN ..."

check_file() {
  local f="$1"
  if [ -f "$NATIVE_BIN/$f" ]; then
    local sz
    sz=$(stat -c%s "$NATIVE_BIN/$f" 2>/dev/null || echo "?")
    echo -e "  ${GREEN}[OK]${NC}  $f ($sz bytes)"
  else
    echo -e "  ${RED}[MISSING]${NC} $f"
  fi
}

echo ""
echo "=== Build Output ==="

check_file "mppatch_core.dll"
check_file "mppatch_core_wrapper.dll"
check_file "mppatch_core.so"
check_file "lua51.dll"
check_file "luajit_win32.dll"
check_file "luajit_linux.so"

# ---- Copy to output dir ----

if [ -n "$OUTPUT_DIR" ]; then
  info "Copying native binaries to $OUTPUT_DIR ..."
  mkdir -p "$OUTPUT_DIR"
  cp -v "$NATIVE_BIN"/* "$OUTPUT_DIR/"
  info "Copied."
fi

# ---- Windows-side next steps ----

echo ""
echo "=== Next Steps ==="
echo ""
echo "The native binaries are now in:"
echo "  (WSL)  $NATIVE_BIN"
echo "  (Win)  \\\\wsl\$\\${WSL_DISTRO_NAME:-<distro>}$(echo "$NATIVE_BIN" | sed 's|/|\\|g')"
echo ""
echo "From Windows PowerShell, run:"
echo ""
echo "  # Build the GraalVM native image (needs pre-built natives from above)"
echo "  sbt nativeImage"
echo ""
echo "  # Or test the CLI directly via SBT:"
echo "  sbt cli -- check --json"
echo ""
echo "  # Full build + installer packaging:"
echo "  sbt clean dist"
