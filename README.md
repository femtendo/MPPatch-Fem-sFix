# Fem's MPPatch Launcher

```
~~~~~~~~~~~~~~~~
   / \__
  (    @\___
  /         O
 /   (_____/
/_____/   U
~~~~~~~~~~~~~~~~
```

**Fem's MPPatch Launcher** is a patch and launcher for Civilization V that lets you
use mods in multiplayer without any special preparation — no shared folders, no
manual file swaps, no "works on my machine."

It supports the Steam versions of Civilization V on **Windows** and **Linux**.
macOS works too, if you run the Windows version of Civ 5 through Proton.

## Why this fork exists

The original [MPPatch](https://github.com/Lymia/MPPatch) hadn't been updated since
2021, and it stopped installing correctly on the current Steam build of Civ 5. This
fork brings the tool up to date with the latest Steam release, fixes long-standing
multiplayer bugs, and is being rebuilt into a full mod organizer + launcher so that
playing modded multiplayer is a two-click affair.

## Features

- Multiplayer mod support — enable mods in MP lobbies with everything synced
- Support for the current Steam version of Civilization V
- Works with popular Workshop mods (see the wiki for tested compatibility)
- Clean game exit — no more zombie processes after quitting
- Open source, MIT licensed

## Installation

1. Download the latest installer from the
   [Releases page](https://github.com/femtendo/MPPatch-Fem-sFix/releases).
2. Run it and point it at your Civ 5 installation (it usually finds Steam itself).
3. Launch Civ 5 normally — the multiplayer menu now has mod support built in.

Detailed instructions live in the [wiki](https://github.com/femtendo/MPPatch-Fem-sFix/wiki).

## Troubleshooting

If something goes wrong during installation, the installer writes a log to:

- **Windows:** `%LOCALAPPDATA%\MPPatch\logs\`
- **Linux:** `~/.local/share/MPPatch/logs/`

Attach that log when reporting an issue. Bug reports are welcome on the
[issue tracker](https://github.com/femtendo/MPPatch-Fem-sFix/issues).

## Compiling from source

MPPatch builds the native patch components in Rust and packages them with a
Scala-based installer. A containerized build environment matching CI:

```bash
git clone --recurse-submodules https://github.com/femtendo/MPPatch-Fem-sFix.git
cd MPPatch-Fem-sFix
# Core patch components (Rust):
docker run --rm --platform linux/amd64 -v "$PWD":/src ubuntu:24.04 bash -c \
  "apt-get update && apt-get install -y curl build-essential pkg-config git python3 \
   && curl --proto '=https' -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain nightly-2025-02-01 --profile minimal \
   && . \$HOME/.cargo/env && cd src/patch/mppatch-core && cargo build --release"
```

To build the full installer, install the deps from `scripts/ci/install-deps.sh`
and run `sbt clean dist`. Releases are produced automatically by GitHub Actions
on every tag.

## Credits

- [Lymia Kanokawa](https://github.com/Lymia) — original MPPatch author
- [VeryHarry7](https://github.com/VeryHarry7) — support for the current Civ 5 Steam build ([upstream PR #98](https://github.com/Lymia/MPPatch/pull/98))
- Everyone who's reported bugs and tested multiplayer sessions

Pull requests welcome. :)

Have fun~ ~fem
