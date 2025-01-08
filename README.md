
# Kalix

## Description

To be completed.


## Licence

This project is licensed under the Mozilla Public License Version 2.0, 
which can be found online at https://www.mozilla.org/en-US/MPL/2.0/ and in the file 
"LICENSE.txt" in this repository.


## Current work (and backlog)

https://www.notion.so/chasegan/Development-tasks-14c3cd7417a280a79bfcc0405e9d75a1 

https://github.com/users/chasegan/projects/1

- TOML for the model format. Maybe call these files *.kx for "kalix"
- Optional fast compressed extendable multi-timeseries format (like Pixie, based on Facebook's Gorilla algorithm).
  - Use a single codec
  - Index file = *.kin = kalix index file
  - Binary file = *.kbn = kalix binary file 
- CSV reader and writer. Reader should be flexible with date stamps, writer should be strict, using a choice of these depending on granularity required:
  - "yyyy-MM-dd"
  - "yyyy-MM-dd'T'HH:mm:ss.SSS" 
- How can we think about global optimisation and parameter uncertainty estimators together?
- Implement global optimisation:
   - DE 
     - https://en.wikipedia.org/wiki/Differential_evolution
     - https://machinelearningmastery.com/differential-evolution-from-scratch-in-python/
   - DREAM
     - https://www.sasview.org/docs/user/qtgui/Perspectives/Fitting/optimizer.html
   - CMAES 
     - https://en.wikipedia.org/wiki/CMA-ES
  - SCE (and SCEM?)
- https://blog.logrocket.com/understanding-inheritance-other-limitations-rust/


## Basic development instructions

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0) Setting up dev environment
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

To get started you will need:
  - Rust: install rust using rustup from https://www.rust-lang.org/tools/install
    - "cargo version" to check version (Current version = 1.68.0)
    - "cargo update" to update to the latest version.
  - Git: im a big fan of Fork https://git-fork.com/
  - IDE: VSCode 
    - Extension "rust-analyzer" with setting "rust-analyzer.check.command": "clippy"

~~~~~~~~~~~~~~~~~~~~~~
1) Running the program
~~~~~~~~~~~~~~~~~~~~~~

Run the program using 
> cargo run
This will compile the code and run the program via its "main()" function, spiting out any warnings or errors in the process.

~~~~~~~~~~~~~~~~
2) Running tests
~~~~~~~~~~~~~~~~

Run all tests by using:
> cargo test

~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
3) Building an executable for deployment
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Build by using:
> cargo build
This will generate a debug binary targeted at the host architecture (e.g. x64) in the folder ./target/debug/ 

If you want to build a release version use either of these instead:
> cargo build --release
> cargo build -r
This will generate a release binary in the folder ./target/release/

To build for a different architecture, use the following:
> cargo build --target triple 
where the general format of the triple is <arch><sub>-<vendor>-<sys>-<abi>. Run rustc --print target-list for a list of supported targets. This flag may be specified multiple times. At the time of writing there were 199 supported targets. These are listed at the bottom of this document. The most important are probably:
   aarch64-apple-darwin
   x86_64-pc-windows-msvc

~~~~~~~~~~~~~~~~~~~~
4) Getting more help
~~~~~~~~~~~~~~~~~~~~

Use either of the following command to get top-level help
> cargo help
> cargo --help

And use the following to get more information on a specific command.
> cargo help <command>

~~~~~~~~~~~~~~~~~~~~~
A) Appendix - Targets
~~~~~~~~~~~~~~~~~~~~~

aarch64-apple-darwin
aarch64-apple-ios
aarch64-apple-ios-macabi
aarch64-apple-ios-sim
aarch64-apple-tvos
aarch64-apple-watchos-sim
aarch64-fuchsia
aarch64-kmc-solid_asp3
aarch64-linux-android
aarch64-nintendo-switch-freestanding
aarch64-pc-windows-gnullvm
aarch64-pc-windows-msvc
aarch64-unknown-freebsd
aarch64-unknown-fuchsia
aarch64-unknown-hermit
aarch64-unknown-linux-gnu
aarch64-unknown-linux-gnu_ilp32
aarch64-unknown-linux-musl
aarch64-unknown-netbsd
aarch64-unknown-none
aarch64-unknown-none-softfloat
aarch64-unknown-nto-qnx710
aarch64-unknown-openbsd
aarch64-unknown-redox
aarch64-unknown-uefi
aarch64-uwp-windows-msvc
aarch64-wrs-vxworks
aarch64_be-unknown-linux-gnu
aarch64_be-unknown-linux-gnu_ilp32
arm-linux-androideabi
arm-unknown-linux-gnueabi
arm-unknown-linux-gnueabihf
arm-unknown-linux-musleabi
arm-unknown-linux-musleabihf
arm64_32-apple-watchos
armeb-unknown-linux-gnueabi
armebv7r-none-eabi
armebv7r-none-eabihf
armv4t-none-eabi
armv4t-unknown-linux-gnueabi
armv5te-none-eabi
armv5te-unknown-linux-gnueabi
armv5te-unknown-linux-musleabi
armv5te-unknown-linux-uclibceabi
armv6-unknown-freebsd
armv6-unknown-netbsd-eabihf
armv6k-nintendo-3ds
armv7-apple-ios
armv7-linux-androideabi
armv7-sony-vita-newlibeabihf
armv7-unknown-freebsd
armv7-unknown-linux-gnueabi
armv7-unknown-linux-gnueabihf
armv7-unknown-linux-musleabi
armv7-unknown-linux-musleabihf
armv7-unknown-linux-uclibceabi
armv7-unknown-linux-uclibceabihf
armv7-unknown-netbsd-eabihf
armv7-wrs-vxworks-eabihf
armv7a-kmc-solid_asp3-eabi
armv7a-kmc-solid_asp3-eabihf
armv7a-none-eabi
armv7a-none-eabihf
armv7k-apple-watchos
armv7r-none-eabi
armv7r-none-eabihf
armv7s-apple-ios
asmjs-unknown-emscripten
avr-unknown-gnu-atmega328
bpfeb-unknown-none
bpfel-unknown-none
hexagon-unknown-linux-musl
i386-apple-ios
i586-pc-windows-msvc
i586-unknown-linux-gnu
i586-unknown-linux-musl
i686-apple-darwin
i686-linux-android
i686-pc-windows-gnu
i686-pc-windows-msvc
i686-unknown-freebsd
i686-unknown-haiku
i686-unknown-linux-gnu
i686-unknown-linux-musl
i686-unknown-netbsd
i686-unknown-openbsd
i686-unknown-uefi
i686-uwp-windows-gnu
i686-uwp-windows-msvc
i686-wrs-vxworks
m68k-unknown-linux-gnu
mips-unknown-linux-gnu
mips-unknown-linux-musl
mips-unknown-linux-uclibc
mips64-openwrt-linux-musl
mips64-unknown-linux-gnuabi64
mips64-unknown-linux-muslabi64
mips64el-unknown-linux-gnuabi64
mips64el-unknown-linux-muslabi64
mipsel-sony-psp
mipsel-sony-psx
mipsel-unknown-linux-gnu
mipsel-unknown-linux-musl
mipsel-unknown-linux-uclibc
mipsel-unknown-none
mipsisa32r6-unknown-linux-gnu
mipsisa32r6el-unknown-linux-gnu
mipsisa64r6-unknown-linux-gnuabi64
mipsisa64r6el-unknown-linux-gnuabi64
msp430-none-elf
nvptx64-nvidia-cuda
powerpc-unknown-freebsd
powerpc-unknown-linux-gnu
powerpc-unknown-linux-gnuspe
powerpc-unknown-linux-musl
powerpc-unknown-netbsd
powerpc-unknown-openbsd
powerpc-wrs-vxworks
powerpc-wrs-vxworks-spe
powerpc64-ibm-aix
powerpc64-unknown-freebsd
powerpc64-unknown-linux-gnu
powerpc64-unknown-linux-musl
powerpc64-unknown-openbsd
powerpc64-wrs-vxworks
powerpc64le-unknown-freebsd
powerpc64le-unknown-linux-gnu
powerpc64le-unknown-linux-musl
riscv32gc-unknown-linux-gnu
riscv32gc-unknown-linux-musl
riscv32i-unknown-none-elf
riscv32im-unknown-none-elf
riscv32imac-unknown-none-elf
riscv32imac-unknown-xous-elf
riscv32imc-esp-espidf
riscv32imc-unknown-none-elf
riscv64gc-unknown-freebsd
riscv64gc-unknown-linux-gnu
riscv64gc-unknown-linux-musl
riscv64gc-unknown-none-elf
riscv64gc-unknown-openbsd
riscv64imac-unknown-none-elf
s390x-unknown-linux-gnu
s390x-unknown-linux-musl
sparc-unknown-linux-gnu
sparc64-unknown-linux-gnu
sparc64-unknown-netbsd
sparc64-unknown-openbsd
sparcv9-sun-solaris
thumbv4t-none-eabi
thumbv5te-none-eabi
thumbv6m-none-eabi
thumbv7a-pc-windows-msvc
thumbv7a-uwp-windows-msvc
thumbv7em-none-eabi
thumbv7em-none-eabihf
thumbv7m-none-eabi
thumbv7neon-linux-androideabi
thumbv7neon-unknown-linux-gnueabihf
thumbv7neon-unknown-linux-musleabihf
thumbv8m.base-none-eabi
thumbv8m.main-none-eabi
thumbv8m.main-none-eabihf
wasm32-unknown-emscripten
wasm32-unknown-unknown
wasm32-wasi
wasm64-unknown-unknown
x86_64-apple-darwin
x86_64-apple-ios
x86_64-apple-ios-macabi
x86_64-apple-tvos
x86_64-apple-watchos-sim
x86_64-fortanix-unknown-sgx
x86_64-fuchsia
x86_64-linux-android
x86_64-pc-nto-qnx710
x86_64-pc-solaris
x86_64-pc-windows-gnu
x86_64-pc-windows-gnullvm
x86_64-pc-windows-msvc
x86_64-sun-solaris
x86_64-unknown-dragonfly
x86_64-unknown-freebsd
x86_64-unknown-fuchsia
x86_64-unknown-haiku
x86_64-unknown-hermit
x86_64-unknown-illumos
x86_64-unknown-l4re-uclibc
x86_64-unknown-linux-gnu
x86_64-unknown-linux-gnux32
x86_64-unknown-linux-musl
x86_64-unknown-netbsd
x86_64-unknown-none
x86_64-unknown-openbsd
x86_64-unknown-redox
x86_64-unknown-uefi
x86_64-uwp-windows-gnu
x86_64-uwp-windows-msvc
x86_64-wrs-vxworks
