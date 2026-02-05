plugins {
    application
    java
    id("org.beryx.runtime") version "1.13.1"
}

// Read version from root VERSION file
val kalixVersion = file("../VERSION").readText().trim()

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
    implementation("org.kordamp.ikonli:ikonli-core:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-swing:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.3.1")
    
    // RSyntaxTextArea for enhanced text components
    implementation("com.fifesoft:rsyntaxtextarea:3.3.4")
    implementation("com.fifesoft:autocomplete:3.3.2")

    // Diff utilities for model comparison
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // JNA for Windows native API calls (AppUserModelID for taskbar pinning)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.kalix.ide.KalixIDE")
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

        // Use .ico on Windows, .png on other platforms
        val iconFile = if (System.getProperty("os.name").lowercase().contains("win")) {
            file("src/main/resources/icons/kalix.ico")
        } else {
            file("src/main/resources/icons/kalix-256.png")
        }

        imageOptions = listOf(
            "--app-version", kalixVersion,
            "--vendor", "Kalix",
            "--copyright", "Copyright 2024-2025 Kalix",
            "--icon", iconFile.absolutePath
        )

        installerOptions = listOf(
            "--win-per-user-install",
            "--win-dir-chooser",
            "--win-menu",
            "--win-shortcut"
        )
    }
}

