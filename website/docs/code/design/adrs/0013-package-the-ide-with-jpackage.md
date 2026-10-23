---
title: "ADR-0013: Package the IDE with the JDK's jpackage, not a plugin"
---

# ADR-0013: Package the IDE with the JDK's jpackage, not a plugin

- **Status**: Proposed
- **Date**: 2026-10-23
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide, build

## Context and problem statement

The IDE ships as a self-contained app image: the application jars plus a
runtime cut from the JDK with jlink, wrapped by jpackage into
`KalixIDE.app` on macOS and a `KalixIDE/` directory elsewhere. The release
scripts and the Windows MSI build consume that directory.

Until October 2026 the image was produced by the `org.beryx.runtime` Gradle
plugin. Moving the IDE to JDK 25 forced Gradle 9, and Gradle 9 forced the
plugin to its 2.0 line, which raised the question of whether the plugin
should stay at all. Its 2.0.1 release still calls `Task.project` at
execution time, which Gradle 9.8 warns will fail in Gradle 10; the issue
reporting it has had no maintainer reply, nor has the one reporting that
`jpackageHome` is ignored, and the project has carried an open call for
co-maintainers since 2022. The plugin was also doing nothing here that
jpackage does not do itself: given `--add-modules` and `--jlink-options` it
runs jlink for a classpath application, and the module list was already
written by hand.

## Decision drivers

- Manifesto §2.8 — less is more. A plugin is carried forever, with its
  release cadence, its deprecations and its maintainer.
- Manifesto §2.6 — clarity off the hot path. The build should say what it
  does in terms a reader can check against the JDK's own documentation.
- Manifesto §2.2 — transparency. The command that builds the artefact
  users install should be readable in the build file, not reconstructed
  from a plugin's source.

## Considered options

1. **Keep `org.beryx.runtime`**, at 2.0.1.
2. **One `Exec` task calling the toolchain JDK's `jpackage`**, fed by
   `installDist`.
3. **`org.panteleyev.jpackageplugin`** — a thin typed wrapper over
   jpackage for classpath applications.
4. **`org.gradlex.java-module-packaging`** — requires a modular
   application.

## Decision

Chosen option: **2**, because the whole job is one command line the JDK
documents, and nothing else needs maintaining.

1. **The app image is built by the JDK's own `jpackage`, invoked from one
   `Exec` task in `build.gradle.kts`.** The task takes the jars from
   `installDist`, the module list and jlink options from two named lists
   beside it, and the launcher's JVM options from
   `applicationDefaultJvmArgs`, so `gradlew run`, the start scripts and
   the image carry the same flags. Every option it passes is one jpackage
   documents.
2. **The tool comes from the Java toolchain**, not from the JVM running
   Gradle. The compiler, the test JVM and the bundled runtime are then the
   same JDK by construction. `build-msi.bat` wraps the finished image with
   whatever jpackage is on `PATH`; the runtime inside is still the
   toolchain's.
3. **The module list is explicit and justified in place.** jlink is told
   which modules to include, each unusual entry says who needs it, and
   jdeps is the check.
4. **No installer is configured in Gradle.** The pipeline ships the image;
   the Windows MSI is built separately by `build-msi.bat`, so there is one
   way to build an installer. This predates the change and is recorded so
   it is not reopened.

### Consequences

- ✅ No third-party build plugin stands between the IDE and the JDK.
- ✅ Configuration-cache compatible today; nothing to wait on for Gradle 10.
- ✅ On JDK 25 the task reproduces the plugin's image: same launcher
  configuration, `Info.plist` and module set. The runtime's `bin/java` and
  `bin/keytool` are now stripped, which is jpackage's default, and nothing
  referenced them.
- ❌ Options are spelled as strings, not typed DSL. Accepted: there are
  thirteen, and jpackage documents each one.
- ❌ Platform-specific jpackage quirks are ours to handle. Accepted: the
  plugin passed them through untouched.

## Pros and cons of the options

### Option 1: Keep the plugin

- ✅ One-line version bump; DSL unchanged.
- ❌ Already deprecated behaviour under Gradle 9.8; no response upstream.
- ❌ Its remaining value here (module suggestion, a separate jlink image)
  was unused.

### Option 2: `Exec` task calling `jpackage`

- ✅ Zero dependencies, readable against the JDK man page, toolchain-aware.
- ❌ Hand-written, so reviewed by us rather than by a plugin's users.

### Option 3: panteleyev plugin

- ✅ Typed options, actively maintained, config-cache compatible.
- ❌ Another single-maintainer dependency, for what is one command line.

### Option 4: gradlex module packaging

- ✅ Well maintained.
- ❌ Requires a modular application; seven of the IDE's dependencies ship
  no module descriptor, three of them no module name at all, and
  `AutoCompleteManager` reaches into a private field of one by reflection.

## Worked example

Adding a module to the runtime is one line in `runtimeModules`, with a
comment saying which dependency needs it. Checking the image after a
change is reading two files: `app/KalixIDE.cfg` for the classpath and
`java-options`, and `runtime/.../release` for `MODULES`.

## Enforcement

- **Structural**: §2. The task resolves `jpackage` from the Java
  toolchain, so another JDK cannot be used without editing the build.
  `build-portable-zip.{sh,bat}` call `gradlew jpackageImage` and nothing
  else produces the image.
- **Advisory**: §1, §3 and §4 are held by review and by the comments
  beside the lists.

## Links and references

- Issue: [#350](https://github.com/chasegan/Kalix/issues/350) (the
  original JDK 25 scoping, which flagged the plugin's bus factor).
- Related: [ADR-0011](0011-no-scheduled-dependency-audit.md), the same
  reluctance to carry a build dependency for no present need.
- External: [jpackage](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html),
  [badass-runtime-plugin issue #166](https://github.com/beryx/badass-runtime-plugin/issues/166).

## Amendments

*None yet.*
