#!/bin/bash
set -euo pipefail

# ============================================================
# MPPatch Fem's Fix — Full Build Script (Ubuntu)
# Builds deps, compiles everything, produces AppImage.
# Run from the repo root:
#   chmod +x scripts/build-femsfix.sh && ./scripts/build-femsfix.sh
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "=== Build directory: $BUILD_DIR ==="
cd "$BUILD_DIR"

# ---- Phase 0: System dependencies ----
echo "=== Phase 0: Installing system dependencies ==="
PHASE0_PKGS="build-essential clang llvm-dev lld gcc-mingw-w64-i686 gcc-multilib nasm python3 wget git curl pkg-config"
NEED_INSTALL=""
for pkg in $PHASE0_PKGS; do
  if ! dpkg -l "$pkg" 2>/dev/null | grep -q "^ii"; then
    NEED_INSTALL="$NEED_INSTALL $pkg"
  fi
done
if [ -n "$NEED_INSTALL" ]; then
  sudo -n apt-get update -qq 2>/dev/null || sudo apt-get update -qq
  sudo -n apt-get install -y -qq $NEED_INSTALL 2>/dev/null || sudo apt-get install -y -qq $NEED_INSTALL
else
  echo "All system packages already installed."
fi

echo "=== Phase 0 complete ==="

# ---- Phase 1: Rust nightly + targets ----
echo "=== Phase 1: Installing Rust nightly ==="
if ! command -v rustup &>/dev/null; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    . "$HOME/.cargo/env"
fi

# Try the pinned nightly first; fall back to latest if unavailable
RUST_NIGHTLY="nightly-2025-02-01"
if rustup toolchain list 2>/dev/null | grep -q "$RUST_NIGHTLY"; then
    echo "Toolchain $RUST_NIGHTLY already installed."
elif rustup toolchain install "$RUST_NIGHTLY" 2>/dev/null; then
    echo "Toolchain $RUST_NIGHTLY installed."
else
    echo "WARNING: $RUST_NIGHTLY not available (too old), falling back to latest nightly."
    RUST_NIGHTLY="nightly"
    rustup toolchain install "$RUST_NIGHTLY"
    # Update rust-toolchain.toml to match
    sed -i "s|channel = \"nightly-2025-02-01\"|channel = \"nightly\"|" \
        "$BUILD_DIR/src/patch/mppatch-core/rust-toolchain.toml"
fi

rustup target add --toolchain "$RUST_NIGHTLY" \
    i686-pc-windows-gnu \
    i686-unknown-linux-gnu

echo "=== Phase 1 complete: Rust nightly=$RUST_NIGHTLY ==="

# ---- Phase 2: SBT (Scala Build Tool) ----
echo "=== Phase 2: Installing SBT ==="
if ! command -v sbt &>/dev/null; then
    curl -fsSL https://github.com/sbt/sbt/releases/download/v1.9.9/sbt-1.9.9.tgz \
        | sudo tar xz -C /usr/local
    sudo ln -sf /usr/local/sbt/bin/sbt /usr/local/bin/sbt
fi

# Also install Java if missing (SBT bundles its own, but native-image needs JDK)
if ! command -v java &>/dev/null; then
    echo "Installing JDK 21..."
    sudo -n apt-get install -y -qq openjdk-21-jdk 2>/dev/null || sudo apt-get install -y -qq openjdk-21-jdk
fi

echo "=== Phase 2 complete ==="

# ---- Phase 3: Ensure LuaJIT submodule is initialized ----
echo "=== Phase 3: Initializing LuaJIT submodule ==="
git submodule update --init
echo "=== Phase 3 complete ==="

# ---- Phase 4: Build native binaries (Rust + LuaJIT + Win32 wrapper) ----
echo "=== Phase 4: Building native binaries ==="
export MPPATCH_VERSION="$(sbt --error "print version" 2>/dev/null | head -1 | tr -d '\n' || echo "0.2.0-DIRTY")"
echo "MPPATCH_VERSION=$MPPATCH_VERSION"
sbt buildNative

echo "=== Native binaries built successfully ==="
ls -la target/native-bin/

# Package the native binaries into a tarball for the next phase
echo "Packaging native binaries tarball..."
cd target/native-bin
  tar --gzip -cv -f ../mppatch_ci_natives-linux.tar.gz *
cd "$BUILD_DIR"

echo "=== Phase 4 complete ==="

# ---- Phase 5: Download GraalVM + deps ----
echo "=== Phase 5: Installing GraalVM and build deps ==="
bash scripts/ci/install-deps.sh
echo "=== Phase 5 complete ==="

# ---- Phase 6: Build native-image installer executable ----
echo "=== Phase 6: Building native-image installer ==="
# Native binaries are already in target/native-bin from Phase 4
sbt nativeImage

# Fix permissions on the generated native-image binary
NATIVE_IMAGE_DIR="target/native-image-linux"
if [ -d "$NATIVE_IMAGE_DIR" ]; then
    chmod +x "$NATIVE_IMAGE_DIR"/* 2>/dev/null || true
    echo "Native image contents:"
    ls -la "$NATIVE_IMAGE_DIR/"
fi

echo "=== Phase 6 complete ==="

# ---- Phase 7: Package AppImage installer ----
echo "=== Phase 7: Building AppImage installer ==="
bash scripts/ci/build-installer_linux.sh

echo ""
echo "============================================"
echo "  BUILD COMPLETE!"
echo "============================================"
echo ""
echo "AppImage installer:"
ls -la "$BUILD_DIR"/target/MPPatch-Installer_linux_*.AppImage 2>/dev/null || \
    ls -la "$BUILD_DIR"/target/dist-build/linux/MPPatch_Installer-x86_64.AppImage
echo ""
echo "Native binaries (for Windows Civ5 install):"
ls -la "$BUILD_DIR"/target/native-bin/
echo ""
echo "To deploy to Windows, copy native-bin contents to:"
echo "  <Civ5>/Assets/DLC/MPPatch/"
echo ""
