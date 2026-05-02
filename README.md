~~~~~~~~~~~~~~~~
   / \__
  (    @\___
  /         O
 /   (_____/
/_____/   U

I wanted an easier way to play multiplayer Civ 5 mods.
This intends to be an all-in-one fork.
My hope is that this fork encourages more Civ 5 modding!

As far as I could tell, this tool's original repo was deprecated and wasn't touched 
since 2021. When I tried to spin up some Paper Mario civ leader mods, it simply did 
not install correctly. This fork intends to allign this project with current Steam 
release Civ 5, and make mods work in multiplayer effectively and easy.
Implements pull #98 from the original repo and a few other fixes.

Have fun~
            ~fem
~~~~~~~~~~~~~~~~

This fix required some wonky ubuntu vm file transfers to get working. I will attempt
to uplaod a shell scrpt in the future to auto-build the required native-bin files
based on this repo.

MPPatch (femsfix 2026)
=======

MPPatch is a patch for Civilization V that allows mods to be used in multiplayer without any special preparation.
It supports the Steam versions of Civilization V on Windows and Linux. macOS is supported, but only if you run
the Windows version of Civilization V through Proton.

You can download the [OLD VERSION OF THIS PATCH](https://github.com/Lymia/MPPatch/releases) here.

For original usage instructions, [read the original repo user guide](https://github.com/Lymia/MPPatch/wiki/User-Manual).

Compiling
---------

(TODO: These instructions are heavily outdated. create new instructions for building on windows/linux.)

Develop a build-method outside of linux (hard to test a windows game on linux).
cleanup all build-files like rust from project folder.
auto mod formatting: some mods on workshop don't put themselves in the right Civ 5 folders. autopatch.
Create a detailed wiki for easy player set-up and a guide for any poor soul attempting to assist me with updating.


---------
OLD README.txt:

MPPatch can only be built on Linux systems. The build scripts has only been extensively tested on Arch Linux. You are
on your own for other distributions.

On Arch Linux, you will need the following packages: `base-devel jdk8-openjdk sbt mingw-w64-gcc nasm gcc-multilib
clang llvm`.

The first time you build a release, you must initialize submodules used by MPPatch. To do this, run
`git submodule update --init`.

You will also need to install [osxcross](https://github.com/tpoechtrager/osxcross). After cloning osxcross into a
directory and setting up the xcode tarballs, execute `./build.sh`, then add `osxcross/target/bin` to your `PATH`.

To build a release, use `sbt clean dist`. You can also use `sbt run` to test your local version without building a full
release.

Contributing
------------

Pull requests welcome. :)
