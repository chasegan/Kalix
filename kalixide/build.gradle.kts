plugins {
    application
    java
    id("org.beryx.runtime") version "1.13.1"
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
val jpackageVersion = run {
    val base = kalixVersion.substringBefore("-")
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
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
    implementation("org.ini4j:ini4j:0.5.4")

    // Logging framework
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")
    
    // FlatLaf modern look and feel
    implementation("com.formdev:flatlaf:3.2.5")
    implementation("com.formdev:flatlaf-extras:3.2.5")
    implementation("com.formdev:flatlaf-intellij-themes:3.2.5")
    
    // Ikonli icons
    implementation("org.kordamp.ikonli:ikonli-core:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-swing:12.4.0")
    implementation("org.kordamp.ikonli:ikonli-fontawesome6-pack:12.4.0")
    
    // RSyntaxTextArea for enhanced text components
    implementation("com.fifesoft:rsyntaxtextarea:3.3.4")
    implementation("com.fifesoft:autocomplete:3.3.2")

    // Diff utilities for model comparison
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // JNA for Windows native API calls (AppUserModelID for taskbar pinning)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Directory watching with native FSEvents on macOS (project tree live updates)
    implementation("io.methvin:directory-watcher:0.18.0")

    // Source: https://mvnrepository.com/artifact/net.lingala.zip4j/zip4j
    implementation("net.lingala.zip4j:zip4j:2.11.6")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.kalix.ide.KalixIDE")
    // Grant native access for the Foreign Function & Memory API (Windows "Reveal in File Manager"
    // calls the Shell API directly), so the JVM doesn't print a restricted-method warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23))
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    from("../VERSION") {
        rename { "version.txt" }
    }
}

runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))
    modules.set(listOf(
        "java.desktop",
        "java.logging",
        "java.xml",
        "java.prefs",
        "java.naming",
        "jdk.unsupported"
    ))

    jpackage {
        jpackageHome = System.getProperty("java.home")
        imageName = "KalixIDE"

        // Per-platform launcher icon: .ico (Windows), .icns (macOS, multi-res
        // incl. retina), .png (Linux — jpackage won't accept an .icns there).
        // All three are regenerated from the master SVG by ../make-icons.sh.
        val osName = System.getProperty("os.name").lowercase()
        val iconFile = when {
            osName.contains("win") -> file("src/main/resources/icons/kalix.ico")
            osName.contains("mac") -> file("src/main/resources/icons/kalix.icns")
            else -> file("src/main/resources/icons/kalix-256.png")
        }

        imageOptions = listOf(
            "--app-version", jpackageVersion,
            "--vendor", "Kalix",
            "--copyright", "Copyright 2024-2025 Kalix",
            "--icon", iconFile.absolutePath
        )

        // No installerOptions here on purpose: the release pipeline builds only
        // the app *image* (jpackageImage), and the Windows MSI is produced
        // separately by build-msi.bat (per-machine, for Ivanti Trusted
        // Ownership). Keeping installer config out of Gradle avoids a second,
        // divergent way to build an installer.
    }
}

