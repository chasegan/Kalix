---
title: "Start Developing"
---

# Start Developing

Whether you're reporting a bug, building a new feature in KalixIDE, or working on the simulation engine, start here.

## I want to report a bug or request a feature

If you want to report a bug or request a new feature, you can reach out to the development team using the contact details below. We track development tasks on github (<https://github.com/chasegan/Kalix/issues>), so you can see what is happening at all times.

- Contact details

Kalix is open source and free but development costs time. If you are requesting development, please consider supporting the project.

## I want to play with the code by myself

If you are curious about development, you can take a copy of the source code and tinker to your heart’s content. The [MPL2.0 licence](https://www.mozilla.org/en-US/MPL/2.0/) means you are free to use and modify the code, provided that any modifications are made available to the public under the same license.

The best way to do this is:

1. Read about [The Dev Stack](design/dev-stack.md) to get an overview of what is involved.

2. Get the code:
   1. Make a GitHub account for yourself (if you don’t already have one)
   2. Go to the Kalix GitHub page (<https://github.com/chasegan/Kalix>) and look for a button that says “Fork”. Forking will take a copy of the official repository and put it into your own GitHub account.
   3. Go to your fork of Kalix (under your GitHub account) and look for a button that says “Clone”. This will show you a “git clone” command that you can use to copy the code to your computer.
   4. At this point you have a copy of the code on your computer, and also your own remote repository under your own GitHub account. You can explore the code using a text editor. The next step will help you compile the code and run your own version of Kalix!

3. Compile and run your own version of Kalix:
   1. Install compilers and IDEs:
      1. **Rust toolchain**: <https://rustup.rs/> — RustRover uses this under the hood
      2. **JDK 23**: [https://adoptium.net/](start-developing.md) — pick the Temurin 23 installer for your OS
      3. **RustRover**: <https://www.jetbrains.com/rust/>
      4. **IntelliJ IDEA Community Edition**: <https://www.jetbrains.com/idea/download/>
      5. You can confirm Rust and Java are installed by opening a terminal and running `rustc --version` and `java --version`. Each should print a version number.
   2. Build the CLI simulation engine “kalix” in RustRover:
      1. Open RustRover and choose **File → Open**, then select the root of your local Kalix folder.
      2. Open the integrated terminal (**View → Tool Windows → Terminal**) and run: `cargo build --release`.
      3. The compiled binary will land at target/release/kalix (or kalix.exe on Windows). The first build downloads a lot of dependencies and may take several minutes.
   3. Build and run the GUI application “KalixIDE” using IntelliJ:
      1. Open IntelliJ IDEA and choose **File → Open**, then select the kalixide subfolder of your Kalix repo (not the repo root). IntelliJ will spot the Gradle build script and import it as a Gradle project. This takes a few minutes the first time as Gradle downloads everything it needs.
      2. Find the **Gradle** panel on the right edge of the IntelliJ window. Expand **kalixide → Tasks → application** and double-click **run**. The KalixIDE window will open.

The “build-portable-zip” scripts are the official distribution build scripts. If the script (for your operating system) doesn’t run properly it probably indicates that your development environment isn’t correctly set up. Use the error messages to get some help from your favourite AI.

1. Run the distribution build scripts from the repository root:
   1. `./build-portable-zip.sh` on macOS / Linux, or
   2. `build-portable-zip.bat` on Windows.

2. The output zip lands in dist/. This is the same script the official release workflow uses.

## I want to set up a remote team to be part of Kalix development (⌐■\_■)

If you are part of an organisation with an interest in Kalix, a some capacity and inclination to improve it, you may want to set up a remote Kalix dev team. Everyone stands to benefit from collaboration. This may include:

- Collaboration with the core team on the roadmap for core version.

- Participation in sessions to prioritise tasks, and share effort on those which are mutually beneficial.

- Sharing of science and modelling methodologies.

- Your team working on a remote fork, with changes fed back to the core version through pull-requests.

Reach out to the core team (Contact details) to discuss how we might work together.

## I am joining the core team (✿◠‿◠)!

If you are joining the core team it means you will be working directly inside the core repository, and will be responsible for:

- Making Kalix faster

- Making Kalix more transparent

- Making Kalix more robust

- Making Kalix more scientifically sound

- Making Kalix more fun

Reach out to the core team (Contact details) for a hug. This is optional.
