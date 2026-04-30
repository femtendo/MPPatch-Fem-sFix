#!/bin/bash
set -euo pipefail

# ============================================================
# MPPatch Fem's Fix — Full Build Script (Ubuntu)
# Clones, builds deps, compiles everything, produces AppImage.
# Run on a fresh Ubuntu 22.04+ VM:
#   chmod +x build-femsfix.sh && ./build-femsfix.sh
# ============================================================

REPO_URL="https://github.com/femtendo/MPPatch-Fem-sFix.git"
BRANCH="master"
BUILD_DIR="$HOME/mppatch-build"

# ---- Phase 0: System dependencies ----
echo "=== Phase 0: Installing system dependencies ==="
sudo apt-get update -qq
sudo apt-get install -y -qq \
    build-essential \
    clang \
    llvm-dev \
    lld \
    gcc-mingw-w64-i686 \
    gcc-multilib \
    python3 \
    wget \
    git \
    curl \
    pkg-config

# ---- Phase 1: Rust nightly + targets ----
echo "=== Phase 1: Installing Rust nightly ==="
if ! command -v rustup &>/dev/null; then
    curl --proto '=forcehttps' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    . "$HOME/.cargo/env"
fi
rustup toolchain install nightly-2025-02-01
rustup target add --toolchain nightly-2025-02-01 \
    i686-pc-windows-gnu \
    i686-unknown-linux-gnu

# ---- Phase 2: SBT (Scala Build Tool) ----
echo "=== Phase 2: Installing SBT ==="
if ! command -v sbt &>/dev/null; then
    curl -fsSL https://github.com/sbt/sbt/releases/download/v1.9.9/sbt-1.9.9.tgz \
        | sudo tar xz -C /usr/local
    sudo ln -sf /usr/local/sbt/bin/sbt /usr/local/bin/sbt
fi

# ---- Phase 3: Clone repo ----
echo "=== Phase 3: Cloning MPPatch-Fem-sFix ==="
if [ ! -d "$BUILD_DIR" ]; then
    git clone --branch "$BRANCH" "$REPO_URL" "$BUILD_DIR"
else
    echo "Already cloned, pulling latest..."
    cd "$BUILD_DIR" && git pull
fi
cd "$BUILD_DIR"

# ---- Phase 4: LuaJIT submodule ----
echo "=== Phase 4: Initializing LuaJIT submodule ==="
git submodule update --init

# ---- Phase 5: Build native binaries (Rust + LuaJIT + Win32 wrapper) ----
echo "=== Phase 5: Building native binaries ==="
export MPPATCH_VERSION="$(sbt --error "print version" 2>/dev/null | head -1 | tr -d '\n')"
echo "MPPATCH_VERSION=$MPPATCH_VERSION"
sbt buildNative

echo "=== Native binaries built successfully ==="
ls -la target/native-bin/

# ---- Phase 6: Download GraalVM + deps ----
echo "=== Phase 6: Installing GraalVM and build deps ==="
bash scripts/ci/install-deps.sh

# ---- Phase 7: Build native-image installer executable ----
echo "=== Phase 7: Building native-image installer ==="
# Extract native tarball so nativeImage task can find them
rm -rf target/native-bin
mkdir -p target/native-bin
cd target/native-bin
tar -xzf ../mppatch_ci_natives-linux.tar.gz
cd ../..

rm -rf target/native-image-linux
mkdir -p target/native-image-linux

sbt nativeImage
chmod +x target/native-image-linux/*.so

echo "=== Native image built ==="

# ---- Phase 8: Package AppImage installer ----
echo "=== Phase 8: Building AppImage installer ==="
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
