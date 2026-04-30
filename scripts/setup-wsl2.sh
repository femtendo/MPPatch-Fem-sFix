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

# Run inside WSL2 to install all build dependencies for MPPatch native compilation.
# Idempotent — safe to re-run.
#
# Usage: bash scripts/setup-wsl2.sh

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

# ---- Detect WSL ----

if ! grep -qi "microsoft" /proc/sys/kernel/osrelease 2>/dev/null; then
  error "This script must be run inside WSL (Windows Subsystem for Linux)."
  exit 1
fi

if ! grep -qi "wsl2" /proc/sys/kernel/osrelease 2>/dev/null; then
  error "WSL1 detected. MPPatch native builds require WSL2 (32-bit ELF support needed for LuaJIT)."
  error "Upgrade to WSL2: wsl --set-version <distro> 2"
  exit 1
fi

info "WSL2 confirmed."

# ---- APT packages ----

info "Updating APT package lists..."
sudo apt update -qq

info "Installing APT build dependencies..."
sudo apt install -y --no-install-recommends \
  build-essential \
  clang \
  lld \
  make \
  python3 \
  mingw-w64 \
  gcc-multilib \
  libz-dev \
  wget \
  curl \
  ca-certificates \
  gnupg

# Java 21 — prefer openjdk-21, fall back to openjdk-17
if apt-cache show openjdk-21-jdk-headless &>/dev/null; then
  info "Installing OpenJDK 21..."
  sudo apt install -y --no-install-recommends openjdk-21-jdk-headless
else
  warn "OpenJDK 21 not available; installing OpenJDK 17..."
  sudo apt install -y --no-install-recommends openjdk-17-jdk-headless
fi

# ---- Rust ----

RUST_VERSION="nightly-2025-02-01"

if command -v rustup &>/dev/null; then
  info "rustup found: $(rustup --version)"
else
  info "Installing rustup..."
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain none
  # shellcheck source=/dev/null
  source "$HOME/.cargo/env"
fi

info "Installing Rust $RUST_VERSION with cross-compilation targets..."
rustup toolchain install "$RUST_VERSION" --profile minimal
rustup target add i686-pc-windows-gnu i686-unknown-linux-gnu --toolchain "$RUST_VERSION"

# ---- SBT ----

if command -v sbt &>/dev/null; then
  info "sbt found: $(sbt --version 2>/dev/null || echo 'ok')"
else
  info "Installing SBT..."
  echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
  echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | sudo tee /etc/apt/sources.list.d/sbt_old.list
  curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | \
    sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg 2>/dev/null || \
    sudo apt-key adv --keyserver keyserver.ubuntu.com --recv 99E82A75642AC823
  sudo apt update -qq
  sudo apt install -y sbt
fi

# ---- Verify ----

echo ""
info "=== Verification ==="

check() {
  local label="$1"; shift
  if "$@" &>/dev/null; then
    echo -e "  ${GREEN}[OK]${NC}  $label"
  else
    echo -e "  ${RED}[MISSING]${NC} $label"
  fi
}

check "clang"              clang --version
check "lld (ld.lld)"       ld.lld --version
check "lld-link"           lld-link --version
check "llvm-objdump"       llvm-objdump --version
check "make"               make --version
check "python3"            python3 --version
check "java"               java --version
check "rustc ($RUST_VERSION)"  bash -c "rustc +$RUST_VERSION --version"
check "rustup target win32"    bash -c "rustup +$RUST_VERSION target list --installed | grep -q i686-pc-windows-gnu"
check "rustup target linux"    bash -c "rustup +$RUST_VERSION target list --installed | grep -q i686-unknown-linux-gnu"
check "mingw-w64 (gcc)"   i686-w64-mingw32-gcc --version
check "mingw-w64 (ar)"    i686-w64-mingw32-ar --version

# 32-bit ELF support (critical for LuaJIT host tools)
if echo 'int main(){}' | clang -m32 -x c - -o /tmp/_mppatch_test32 2>/dev/null; then
  rm -f /tmp/_mppatch_test32
  echo -e "  ${GREEN}[OK]${NC}  32-bit ELF (clang -m32)"
else
  echo -e "  ${RED}[MISSING]${NC} 32-bit ELF (clang -m32) — install gcc-multilib"
fi

echo ""
info "Setup complete."
info "Next: run scripts/build-natives-wsl2.sh from the repo root."
