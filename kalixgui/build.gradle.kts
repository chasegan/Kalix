plugins {
    application
    java
}

group = "com.kalix"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("org.ini4j:ini4j:0.5.4")
    
    // FlatLaf modern look and feel
    implementation("com.formdev:flatlaf:3.2.5")
    implementation("com.formdev:flatlaf-extras:3.2.5")
    implementation("com.formdev:flatlaf-intellij-themes:3.2.5")
    
    // Ikonli icons
    implementation("org.kordamp.ikonli:ikonli-core:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-swing:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.3.1")
    
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.kalix.gui.KalixGUI")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23))
    }
}

tasks.test {
    useJUnitPlatform()
}

// Task to test CLI integration
tasks.register<JavaExec>("testCli") {
    group = "verification"
    description = "Test CLI integration Phase 1 implementation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kalix.gui.cli.CliTest")
}

// Task to test API discovery
tasks.register<JavaExec>("testApiDiscovery") {
    group = "verification"
    description = "Test API discovery Phase 2 implementation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kalix.gui.cli.ApiDiscoveryTest")
}

// Task to test command execution
tasks.register<JavaExec>("testCommandExecution") {
    group = "verification"
    description = "Test dynamic command execution Phase 3 implementation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kalix.gui.cli.CommandExecutionTest")
}

// Quick test for debugging command execution
tasks.register<JavaExec>("quickTest") {
    group = "verification"
    description = "Quick test of command execution"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.kalix.gui.cli.QuickExecutionTest")
}