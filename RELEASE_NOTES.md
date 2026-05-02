# MPPatch Alpha Release

## What's New

This fork of MPPatch brings Civilization V mod-in-multiplayer support back to life for the current Steam release (1.0.3.279).

### Features
- **Mods in multiplayer** — Host games with active mods, other players auto-sync
- **LuaJIT** — Optional LuaJIT runtime for improved script performance
- **Sentinel mechanism** — New direct in-memory hook on `lGetMemoryUsage`/`lCollectMemoryUsage` intercepts Lua calls via C function pointer, fixing mod detection in the mods menu
- **Cross-platform** — Windows and Linux builds

### Changes from Upstream
- Upgraded to latest Rust nightly (naked_functions → `#[unsafe(naked)]`)
- Fixed `#[cfg(windows)]` guards for Linux cross-compilation
- Updated LuaJIT submodule to latest openresty/luajit2
- Fixed `mainClass` scope for GraalVM native-image build
- Updated MinGW linker configuration for Windows builds
- Added tracing/logging throughout DLL init for debugging load failures

## Installation

1. **Download** the installer for your platform
2. **Run as administrator** (required to modify the Civ5 installation directory)
3. The installer will:
   - Backup your existing Civ5 DLLs
   - Deploy the MPPatch native library
   - Install Lua hook files for mods-in-multiplayer support
   - Configure LuaJIT runtime (optional)

## Known Issues

- **Administrator required** — Civ5 installs to Program Files, and the installer needs elevation to write there
- **Proxy DLL limitations** — Some anti-virus software may flag DLL proxying behavior (false positive)
- **Civ5 version locked** — Only supports Steam 1.0.3.279 (SHA-256: `f9563739...`)
- **No code signing** — Installer is unsigned; SmartScreen/AV may warn (see below)
- **Experimental** — This is an alpha release. Game crashes may occur. Save often.
- **Multiplayer** — All players must have the same MPPatch version installed

## Build Details

Built from commit: `45cbc2f`
