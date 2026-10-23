plugins {
    application
    java
    checkstyle
}

// Read version from root VERSION file
val kalixVersion = file("../VERSION").readText().trim()
// jpackage --app-version requires N[.N[.N]] with no pre-release suffix, and on macOS the
// major must be >= 1 (CFBundleShortVersionString rejects a 0 major). While we're pre-1.0 we
// substitute a *sentinel* major of 9999 on macOS only — hydrology's conventional missing-value
// flag, and obviously not a real release, so it can't be mistaken for one in Get Info or bug
// reports and won't ever collide with a real version we ship. The real version lives in
// version.txt (titlebar/About) and is untouched, so this only affects the launcher metadata
// macOS records in Get Info. Once the real major reaches >= 1 the substitution stops.
val osName = System.getProperty("os.name").lowercase()
val isMac = osName.contains("mac")
val isWindows = osName.contains("win")
val jpackageVersion = run {
    val base = kalixVersion.substringBefore("-")
    val parts = base.split(".").toMutableList()
    if (isMac && (parts.firstOrNull()?.toIntOrNull() ?: 0) < 1) {
        parts[0] = "9999" // sentinel; macOS bundle metadata only — version.txt keeps the real version
    }
    parts.joinToString(".")
}

group = "com.kalix"
version = kalixVersion

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")

    // Logging: SLF4J over java.util.logging (slf4j-jdk14), configured by
    // src/main/resources/com/kalix/ide/logging/logging.properties.
    implementation("org.slf4j:slf4j-api:2.0.9")
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.9")
    
    // FlatLaf modern look and feel
    implementation("com.formdev:flatlaf:3.2.5")
    
    // Ikonli icons
    implementation("org.kordamp.ikonli:ikonli-core:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-swing:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-fontawesome6-pack:12.4.0")
    
    // RSyntaxTextArea for enhanced text components
    implementation("com.fifesoft:rsyntaxtextarea:3.5.4")
    implementation("com.fifesoft:autocomplete:3.3.2")

    // Diff utilities for model comparison
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // jna is directory-watcher's (FSEvents on macOS); pinned here so the transitive is not an old one.
    implementation("net.java.dev.jna:jna:5.14.0")

    // Directory watching with native FSEvents on macOS (project tree live updates)
    implementation("io.methvin:directory-watcher:0.18.0")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 no longer puts the JUnit Platform launcher on the test runtime classpath
    // for us; the version comes from the BOM above.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.kalix.ide.KalixIDE")
    // Grant native access for JNA (taskbar AppUserModelID, directory watcher) and the
    // Foreign Function & Memory API (Windows "Reveal in File Manager" calls the Shell API
    // directly). JDK 24+ (JEP 472) warns on restricted native access by default and will
    // eventually block it; this covers `gradlew run`, the installDist start scripts, the
    // jpackage app image and (below) the test JVM. Launchers that bypass the application
    // plugin — an IDE "Application" run configuration, bare `java -cp` — must pass the
    // same flag in their own VM options.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// One rule, and no warnings tolerated: no wildcard imports, static or otherwise
// (config/checkstyle/checkstyle.xml). .editorconfig stops the IDE producing them in the
// first place; this is the backstop for anything hand-written or from another editor.
checkstyle {
    toolVersion = "14.3.0"
    maxWarnings = 0
}

tasks.test {
    useJUnitPlatform()
    // The same JVM flags the IDE runs with: FsWatcherTest starts a real directory watcher,
    // which on macOS goes through JNA and would otherwise print a JEP 472 warning.
    jvmArgs(application.applicationDefaultJvmArgs)
    // Run headless everywhere, matching Linux CI. Without this a component that throws
    // HeadlessException passes on a developer's Mac and fails only after push — which is
    // exactly how DocumentWorkspaceViewTest reached main red.
    systemProperty("java.awt.headless", "true")
    testLogging {
        // CI keeps only the console, and "AssertionFailedError at Foo.java:208" cannot
        // say what was expected or what was seen. Failures print in full.
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processResources {
    from("../VERSION") {
        rename { "version.txt" }
    }
}

// ---------------------------------------------------------------------------------------
// App image: build/jpackage/KalixIDE (KalixIDE.app on macOS), consumed by
// ../build-portable-zip.{sh,bat} and ../build-msi.bat. It is made by the toolchain JDK's
// own jpackage, which for a classpath application runs jlink itself, so the bundled
// runtime is cut from the JDK the IDE was compiled against and holds exactly the modules
// listed here. No installer is configured on purpose: the release pipeline ships the app
// image, and the Windows MSI is produced separately by build-msi.bat (per-machine, for
// Ivanti Trusted Ownership), so there is one way to build an installer, not two.

// jdeps --print-module-deps over the install libs gives java.desktop, java.prefs,
// jdk.unsupported and java.sql. java.sql is left out on purpose: only jackson-databind's
// optional SQL type handlers want it. jdeps folds java.logging into java.sql's transitive
// requires, so without it java.logging must be named here (all logging goes through
// java.util.logging); java.xml comes with java.desktop regardless. jdk.unsupported is
// directory-watcher's com.sun.nio.file FILE_TREE modifier on Windows; neither first-party
// code nor any dependency uses sun.misc.Unsafe.
val runtimeModules = listOf(
    "java.desktop",
    "java.logging",
    "java.xml",
    "java.prefs",
    "jdk.unsupported"
)

// Replaces jpackage's default jlink options, which are these minus the compression.
val jlinkOptions = listOf(
    "--strip-native-commands", "--strip-debug", "--compress=zip-6", "--no-header-files", "--no-man-pages"
)

val jpackageTool = javaToolchains.launcherFor(java.toolchain).map {
    it.metadata.installationPath.file(if (isWindows) "bin/jpackage.exe" else "bin/jpackage")
}

tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Builds the self-contained KalixIDE app image with the toolchain's jpackage."

    val appLibs = tasks.installDist.get().destinationDir.resolve("lib")
    val imageDir = layout.buildDirectory.dir("jpackage").get().asFile
    // Per-platform launcher icon: .ico (Windows), .icns (macOS, multi-res incl. retina),
    // .png (Linux — jpackage won't accept an .icns there). All three are regenerated from
    // the master SVG by ../make-icons.sh.
    val iconFile = when {
        isWindows -> file("src/main/resources/icons/kalix.ico")
        isMac -> file("src/main/resources/icons/kalix.icns")
        else -> file("src/main/resources/icons/kalix-256.png")
    }

    inputs.files(tasks.installDist)
    inputs.file(iconFile)
    inputs.property("appVersion", jpackageVersion)
    outputs.dir(imageDir)

    // jpackage refuses to write over an existing app image.
    doFirst { imageDir.deleteRecursively() }

    executable = jpackageTool.get().asFile.absolutePath
    args(
        "--type", "app-image",
        "--dest", imageDir,
        "--name", "KalixIDE",
        "--input", appLibs,
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", application.mainClass.get(),
        "--add-modules", runtimeModules.joinToString(","),
        "--jlink-options", jlinkOptions.joinToString(" "),
        "--app-version", jpackageVersion,
        "--vendor", "Kalix",
        "--copyright", "Copyright 2024-2026 Kalix",
        "--icon", iconFile
    )
    // Same JVM options as every other launch path; see application {}.
    application.applicationDefaultJvmArgs.forEach { args("--java-options", it) }
}
